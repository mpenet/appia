(ns s-exp.appia
  "Router utilities for HTTP request handling using matching on path/method.

  Provides functions for building and matching routes that follow the openapi
  path format.

  Exposes:
  - router: Builds a router from a set of routes.
  - match: Attempts to match a router against a Ring-style request."
  (:require [clojure.string :as str])
  (:import (clojure.lang Keyword MapEntry PersistentArrayMap)
           (java.util Arrays HashMap)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(deftype TrieNode [handler
                   static ; StaticChildren instance, typed after it is defined
                   ^objects patterns
                   param ; child TrieNode for a whole-segment param, or nil
                   param-key
                   wildcard
                   ;; number of params captured on the path to this node
                   ^long offset])

;; Frozen sub-segment pattern entry: parts vector + child TrieNode.
(deftype Pattern [^clojure.lang.IPersistentVector parts ^TrieNode child])

;; Per-method router for tries with dynamic routes: exact maps fully-static
;; URIs to precomputed match results, root is the trie for everything else.
(deftype Trie [^HashMap exact ^TrieNode root ^long slots])

;; Frozen literal children: open-addressing table keyed by String hash.
;; Lookups hash and compare URI regions in place, so they never allocate.
(deftype StaticChildren [^longs hashes ^objects keys ^objects children ^long mask])

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

;; Used only at build time (split-route). Not on the match hot path.
(defn- split-uri-vec [^String path]
  (into [] (remove empty?) (str/split path #"/")))

(defn- split-route
  [path]
  (map parse-segment-pattern (split-uri-vec path)))

;; Captured params live in vals as key/value pairs, the n-th at 2n and 2n+1.
(defn- capture
  [^objects vals ^long slot k v]
  (aset vals (* 2 slot) k)
  (aset vals (inc (* 2 slot)) v))

;; Path-params map of the first n captured pairs of vals. Repeated keys
;; resolve as with assoc: later values win.
(defn- params-map
  [^objects vals ^long n]
  (if (zero? n)
    {}
    (PersistentArrayMap/createAsIfByAssoc
     (if (= (* 2 n) (alength vals))
       vals
       (Arrays/copyOf vals (int (* 2 n)))))))

;; Match a sub-segment parts vector against a URI segment string.
;; Parts alternate between string literals and param keywords.
;; Uses regionMatches for literal comparison to avoid subs allocations.
;; Captures key/value pairs into vals from slot onwards; returns true on success.
(defn- match-parts
  [^clojure.lang.IPersistentVector parts ^String seg ^objects vals ^long slot]
  (let [n (.count parts)
        len (.length seg)]
    (loop [i 0
           pos 0
           slot slot]
      (if (= i n)
        (= pos len)
        (let [part (.nth parts i)]
          (if (instance? Keyword part)
            (if (= i (dec n))
              ;; trailing param: capture rest of segment
              (do (capture vals slot part (.substring seg (int pos)))
                  true)
              ;; param followed by delimiter literal: find delimiter
              (let [^String delim (.nth parts (inc i))
                    dlen (.length delim)
                    delim-idx (if (= dlen 1)
                                (.indexOf seg (int (.charAt delim 0)) (int pos))
                                (.indexOf seg delim (int pos)))]
                (if (neg? delim-idx)
                  false
                  (do (capture vals slot part (.substring seg (int pos) delim-idx))
                      (recur (+ i 2)
                             (+ delim-idx dlen)
                             (inc slot))))))
            ;; literal part: compare in-place with regionMatches (no allocation)
            (let [^String part part
                  plen (.length part)
                  end (+ pos plen)]
              (if (and (<= end len)
                       (.regionMatches seg (int pos) part 0 plen))
                (recur (inc i) end slot)
                false))))))))


;; Constant-time hash of uri[start, end) from its length and its first,
;; middle and last chars. Probes compare the full region, so the hash only
;; needs to spread typical sibling segments.
(defn- region-hash
  ^long [^String uri ^long start ^long end]
  (let [len (- end start)]
    (if (zero? len)
      0
      (-> len
          (* 31) (+ (int (.charAt uri (int start))))
          (* 31) (+ (int (.charAt uri (int (+ start (quot len 2))))))
          (* 31) (+ (int (.charAt uri (int (dec end)))))))))

(defn- slot
  ^long [^long h ^long mask]
  (bit-and (bit-xor h (bit-shift-right h 16)) mask))

;; Build a StaticChildren table from a String -> TrieNode map, or nil if empty.
;; Sized to a power of two at least twice the entry count, so probing always
;; reaches an empty slot.
(defn- static-children
  [m]
  (when (seq m)
    (let [n (* 2 (count m))
          size (long (loop [s 1] (if (< s n) (recur (* 2 s)) s)))
          mask (dec size)
          hashes (long-array size)
          ks (object-array size)
          cs (object-array size)]
      (doseq [[^String k child] m]
        (let [h (region-hash k 0 (.length k))]
          (loop [i (slot h mask)]
            (if (aget ks i)
              (recur (bit-and (inc i) mask))
              (do (aset hashes i h)
                  (aset ks i k)
                  (aset cs i child))))))
      (StaticChildren. hashes ks cs mask))))

;; Find the literal child matching uri[start, end), or nil.
(defn- static-child
  [^StaticChildren t ^String uri ^long start ^long end]
  (let [h (region-hash uri start end)
        len (- end start)
        ^longs hashes (.hashes t)
        ^objects ks (.keys t)
        mask (.mask t)]
    (loop [i (slot h mask)]
      (when-let [^String k (aget ks i)]
        (if (and (== h (aget hashes i))
                 (== len (.length k))
                 (.regionMatches uri (int start) k 0 (int len)))
          (aget ^objects (.children t) i)
          (recur (bit-and (inc i) mask)))))))

;; Build-time trie node structure (plain map, used only during insert-route):
;;   :handler  - handler at this terminal node, or nil
;;   :static   - String -> node map for literal segment children
;;   :patterns - vector of [parts-vec node] for sub-segment children
;;               (tried in insertion order, after static, before :param)
;;   :param    - {:name keyword :child node} for whole-segment param, or nil
;;   :wildcard - handler value directly (not wrapped), or nil
;;
;; At the end of router, freeze-node converts this tree to TrieNode instances
;; for named field access at match time.

(def ^:private empty-node
  {:handler nil :static {} :patterns [] :param nil :wildcard nil})

;; n is the number of params captured on the path to m.
(defn- freeze-node
  ^TrieNode [m ^long n]
  (TrieNode.
   (:handler m)
   (static-children (update-vals (:static m) #(freeze-node % n)))
   (when (seq (:patterns m))
     (let [pats (:patterns m)
           arr (object-array (count pats))]
       (doseq [[i [parts child]] (map-indexed vector pats)]
         (aset arr i (Pattern. parts (freeze-node child (+ n (count (filter keyword? parts)))))))
       arr))
   (when-let [p (:param m)]
     (freeze-node (:child p) (inc n)))
   (:name (:param m))
   (:wildcard m)
   n))

;; Number of capture slots needed to match any route of the map-trie m,
;; n being the number of params captured on the path to m.
(defn- capture-slots [m ^long n]
  (reduce max
          (cond-> n (some? (:wildcard m)) inc)
          (concat (map #(capture-slots % n) (vals (:static m)))
                  (map (fn [[parts child]]
                         (capture-slots child (+ n (count (filter keyword? parts)))))
                       (:patterns m))
                  (when-let [p (:param m)]
                    [(capture-slots (:child p) (inc n))]))))

;; Returns true if the map-trie rooted at m has only static routes
;; (no :param, :patterns, or :wildcard anywhere in the tree).
(defn- static-only? [m]
  (and (nil? (:param m))
       (empty? (:patterns m))
       (nil? (:wildcard m))
       (every? static-only? (vals (:static m)))))

;; Collect the fully-static routes of a map-trie into a HashMap of
;; URI-string -> precomputed match result.
;; prefix accumulates the path so far (without leading slash).
(defn- flatten-static-trie [m ^String prefix ^HashMap out]
  (when-let [h (:handler m)]
    (.put out (if (= prefix "") "/" (str "/" prefix)) (MapEntry/create h {})))
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

;; Walk the URI string directly without pre-splitting into segments.
;; pos = start of the current segment (character after the last '/').
;; Eliminates PersistentVector allocation and .nth dispatch on the hot path.
;; We use 4 args (the max supporting primitive hints) with ^long pos;
;; uri.length() is O(1) on the JVM (cached field) so calling it per frame is free.
;; Captured params go in vals at slots fixed per node at build time, so the
;; params map is built once, from the matching node's captures.
(defn- match-node
  [^TrieNode node ^String uri ^long pos ^objects vals]
  (let [len (long (.length uri))]
    (if (= pos len)
      ;; consumed all input: check for handler or wildcard at this node
      (or (when-let [h (.handler node)] (MapEntry/create h (params-map vals (.offset node))))
          (when-some [w (.wildcard node)] (MapEntry/create w (params-map vals (.offset node)))))
      ;; find end of current segment (next '/' or end of string)
      (let [slash (long (.indexOf uri (int \/) (int pos)))
            seg-end (if (neg? slash) len slash)
            ;; a last segment resumes at len, so the child's terminal check runs
            next-pos (if (= seg-end len) len (inc seg-end))]
        ;; 1. static exact match
        (or (when-let [t (.static node)]
              (when-let [child (static-child t uri pos seg-end)]
                (match-node ^TrieNode child uri next-pos vals)))
            (let [^objects pats (.patterns node)
                  n (if pats (long (alength pats)) 0)
                  ;; segment text is only materialized for the capturing branches
                  seg (when (or pats (.param node))
                        (.substring uri (int pos) (int seg-end)))]
              ;; kept in tail position so the compiler does not wrap it in a fn
              (loop [i 0]
                (if (< i n)
                  ;; 2. sub-segment patterns (tried in insertion order)
                  (or (let [^Pattern pat (aget pats i)]
                        (when (match-parts (.parts pat) seg vals (.offset node))
                          (match-node (.child pat) uri next-pos vals)))
                      (recur (inc i)))
                  (or
                   ;; 3. whole-segment named param
                   (when-let [child (.param node)]
                     (capture vals (.offset node) (.param-key node) seg)
                     (match-node child uri next-pos vals))
                   ;; 4. wildcard -- remainder starts at pos (current segment start)
                   (when-some [w (.wildcard node)]
                     (capture vals (.offset node) :* (.substring uri (int pos)))
                     (MapEntry/create w (params-map vals (inc (.offset node))))))))))))))

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
                     ;; single HashMap of URI -> match result for O(1) lookup.
                     (let [exact (flatten-static-trie m "" (HashMap.))]
                       (if (static-only? m)
                         exact
                         (Trie. exact (freeze-node m 0) (capture-slots m 0))))))))

(defn match
  "Given `matcher` attempts to match against ring request, return match (tuple of
  `data` & `path-params`)"
  [matcher request]
  ;; plain lookups: map destructuring also accepts seqs, at a per-call cost
  (when-let [v (get matcher (get request :request-method))]
    (if (instance? HashMap v)
      ;; fast path: all-static routes -- single URI lookup, no params
      (.get ^HashMap v (get request :uri))
      (let [^Trie t v
            uri (get request :uri)]
        (or
         ;; fully-static routes win over dynamic ones at every segment, so an
         ;; exact hit is what the trie walk would return
         (.get ^HashMap (.exact t) uri)
         ;; trie path: walk URI string directly, no segment pre-splitting
         (let [^String uri uri
               ;; skip leading slash
               start (if (and (pos? (.length uri)) (= \/ (.charAt uri 0))) 1 0)]
           (match-node (.root t) uri start (object-array (* 2 (.slots t))))))))))
