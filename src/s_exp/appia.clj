(ns s-exp.appia
  "Router utilities for HTTP request handling using matching on path/method.

  Provides functions for building and matching routes, adapted from
  clj-simple-router but modified to match on named path parameters
  (e.g. '/foo/{bar}') and HTTP methods instead of simple wildcards.

  Exposes:
  - router: Builds a router from a set of routes.
  - match: Attempts to match a router against a Ring-style request."
  (:import (java.util HashMap)))

(set! *warn-on-reflection* true)

(def ^:private param-re #"\{([^}]+)\}")

(defn- parse-segment-pattern
  [^String segment]
  (if-let [[_ name] (re-matches param-re segment)]
    ;; whole-segment param -> keyword
    (keyword name)
    ;; scan for {param} occurrences in segment
    (let [params (re-seq param-re segment)]
      (if (empty? params)
        ;; pure literal -> string
        segment
        ;; mixed sub-segment: build vector of alternating literals and param keywords
        ;; e.g. "{name}.{ext}" -> [:name "." :ext]
        ;; e.g. "baz:{id}"    -> ["baz:" :id]
        (let [[parts remaining]
              (reduce (fn [[parts ^String s] [token name]]
                        (let [idx (.indexOf s ^String token)
                              parts (cond-> parts (pos? idx) (conj (subs s 0 idx)))]
                          [(conj parts (keyword name))
                           (subs s (+ idx (count token)))]))
                      [[] segment]
                      params)]
          {:parts (cond-> parts (not= "" remaining) (conj remaining))})))))

;; Split a URI path into a vector of segment strings.
;; Walks character-by-character to avoid regex and lazy-seq overhead.
(defn- split-uri
  ^clojure.lang.PersistentVector
  [^String path]
  (let [len (.length path)]
    (loop [i 0 start 0 acc []]
      (if (>= i len)
        (if (> i start) (conj acc (.substring path start i)) acc)
        (if (= \/ (.charAt path i))
          (let [acc (if (> i start) (conj acc (.substring path start i)) acc)]
            (recur (inc i) (inc i) acc))
          (recur (inc i) start acc))))))

;; Walk the URI to find the start offset of the segment at index seg-idx.
;; Only called when a wildcard node matches; cost is acceptable on that path.
(defn- uri-offset-at
  ^long [^String uri ^long seg-idx]
  (let [len (.length uri)]
    (loop [i 0 found 0]
      (if (>= i len)
        len
        (if (= \/ (.charAt uri i))
          (if (= found seg-idx)
            (inc i)
            (recur (inc i) (inc found)))
          (recur (inc i) found))))))

(defn- split-route
  [path]
  (map parse-segment-pattern (split-uri path)))

;; Match a sub-segment parts vector against a URI segment string.
;; Parts alternate between string literals and param keywords.
;; Uses regionMatches for literal comparison to avoid subs allocations.
;; Returns updated params map on success, nil on failure.
(defn- match-parts
  ^clojure.lang.IPersistentMap
  [^clojure.lang.IPersistentVector parts ^String seg params]
  (let [n (.count parts)
        len (.length seg)]
    (loop [i 0
           pos 0
           params params]
      (if (= i n)
        (if (= pos len) params nil)
        (let [part (.nth parts i)]
          (if (keyword? part)
            (if (= i (dec n))
              ;; trailing param: capture rest of segment
              (assoc params part (.substring seg pos))
              ;; param followed by delimiter literal: find delimiter
              (let [^String delim (.nth parts (inc i))
                    dlen (.length delim)
                    delim-idx (if (= dlen 1)
                                (.indexOf seg (int (.charAt delim 0)) pos)
                                (.indexOf seg delim pos))]
                (if (neg? delim-idx)
                  nil
                  (recur (+ i 2)
                         (+ delim-idx dlen)
                         (assoc params part (.substring seg pos delim-idx))))))
            ;; literal part: compare in-place with regionMatches (no allocation)
            (let [^String part part
                  plen (.length part)
                  end (+ pos plen)]
              (if (and (<= end len)
                       (.regionMatches seg pos part 0 plen))
                (recur (inc i) end params)
                nil))))))))

;; Build-time trie node structure (plain map, used only during insert-route):
;;   :handler  - handler at this terminal node, or nil
;;   :static   - String -> node map for literal segment children
;;   :patterns - vector of [parts-vec node] for sub-segment children
;;               (tried in insertion order, after static, before :param)
;;   :param    - {:name keyword :child node} for whole-segment param, or nil
;;   :wildcard - handler value directly (not wrapped), or nil
;;
;; At the end of router, freeze-node converts this tree to Object[] nodes
;; for faster positional access at match time.

(def ^:private empty-node
  {:handler nil :static {} :patterns [] :param nil :wildcard nil})

;; Frozen node layout (Object[5]):
;; slot 0 – handler, 1 – HashMap static, 2 – Object[] patterns, 3 – Object[] param, 4 – wildcard

(defn- freeze-node
  ^objects [m]
  (let [a (object-array 5)]
    (aset a 0 (:handler m))
    (aset a 4 (:wildcard m))
    (aset a 1
          (let [^HashMap hm (HashMap.)]
            (doseq [[k child] (:static m)]
              (.put hm k (freeze-node child)))
            hm))
    (aset a 3
          (when-let [p (:param m)]
            (object-array [(:name p) (freeze-node (:child p))])))
    (aset a 2
          (when (seq (:patterns m))
            (let [pats (:patterns m)
                  arr (object-array (* 2 (count pats)))]
              (doseq [[i [parts child]] (map-indexed vector pats)]
                (aset arr (* 2 i) parts)
                (aset arr (inc (* 2 i)) (freeze-node child)))
              arr)))
    a))

;; Returns true if the map-trie rooted at m has only static routes
;; (no :param, :patterns, or :wildcard anywhere in the tree).
(defn- static-only? [m]
  (and (nil? (:param m))
       (empty? (:patterns m))
       (nil? (:wildcard m))
       (every? static-only? (vals (:static m)))))

;; Flatten a purely-static map-trie into a HashMap of URI-string -> handler.
;; prefix accumulates the path so far (without leading slash).
(defn- flatten-static-trie [m ^String prefix ^HashMap out]
  (when-let [h (:handler m)]
    (.put out (if (= prefix "") "/" (str "/" prefix)) h))
  (doseq [[seg child] (:static m)]
    (flatten-static-trie child
                         (if (= prefix "") seg (str prefix "/" seg))
                         out))
  out)

(defn- insert-route
  [node segments handler]
  (if-let [[seg & rest] (seq segments)]
    (cond
      (= seg "*")
      (assoc node :wildcard handler)

      (string? seg)
      (update-in node [:static seg]
                 (fn [child] (insert-route (or child empty-node) rest handler)))

      (map? seg)
      (let [parts (:parts seg)
            patterns (:patterns node)
            existing (keep-indexed (fn [i [p n]] (when (= p parts) [i n])) patterns)
            [idx child] (first existing)]
        (if idx
          (assoc-in node [:patterns idx 1] (insert-route child rest handler))
          (update node :patterns conj [parts (insert-route empty-node rest handler)])))

      (keyword? seg)
      (update node :param
              (fn [p] {:name seg
                       :child (insert-route (or (:child p) empty-node) rest handler)})))
    (assoc node :handler handler)))

;; match-node is closed over the original URI string, used only in the wildcard
;; branch to substring the remainder without re-joining split segments.
;; A closure is used because Clojure only supports primitive type hints on fns
;; with ≤4 args, and we need ^long idx for unboxed index arithmetic.
(defn- make-match-node
  [^String uri]
  (fn match-node
    [^objects node ^clojure.lang.PersistentVector segments ^long idx params]
    (if (= idx (.count segments))
      (or (when-let [h (aget node 0)] [h params])
          (when-some [w (aget node 4)] [w params]))
      (let [seg (.nth segments idx)
            nxt (inc idx)]
        (or
         ;; 1. static exact match
         (when-let [child (.get ^HashMap (aget node 1) seg)]
           (match-node child segments nxt params))
         ;; 2. sub-segment patterns (tried in insertion order)
         (when-let [^objects pats (aget node 2)]
           (loop [i 0]
             (when (< i (alength pats))
               (or (when-some [p (match-parts (aget pats i) seg params)]
                     (match-node (aget pats (inc i)) segments nxt p))
                   (recur (+ i 2))))))
         ;; 3. whole-segment named param
         (when-let [^objects p (aget node 3)]
           (match-node (aget p 1) segments nxt (assoc params (aget p 0) seg)))
         ;; 4. wildcard — substring original URI from current segment's start offset
         (when-some [w (aget node 4)]
           (let [offset (uri-offset-at uri idx)]
             [w (if (>= offset (.length uri))
                  params
                  (assoc params :* (.substring uri offset)))])))))))

(defn router
  "Given set of routes, builds router"
  [routes]
  (-> (reduce (fn [acc [[method path] handler]]
                (update acc method
                        (fn [trie]
                          (insert-route (or trie empty-node)
                                        (split-route path)
                                        handler))))
              {}
              routes)
      (update-vals (fn [m]
                     ;; If the trie has only static routes, flatten it into a
                     ;; single HashMap of interned-URI -> handler for O(1) lookup.
                     (if (static-only? m)
                       (flatten-static-trie m "" (HashMap.))
                       (freeze-node m))))))

(defn match
  "Given `matcher` attempts to match against ring request, return match (tuple of
  `data` & `path-params`)"
  [matcher {:as _request :keys [request-method uri]}]
  (when-let [v (get matcher request-method)]
    (if (instance? HashMap v)
      ;; fast path: all-static routes — single URI lookup, no params
      (when-let [h (.get ^HashMap v uri)]
        [h {}])
      ;; trie path: parameterised routes
      ((make-match-node uri) ^objects v (split-uri uri) 0 {}))))
