(ns s-exp.appia
  "Router utilities for HTTP request handling using matching on path/method.

  Provides functions for building and matching routes that follow the openapi
  path format.

  Exposes:
  - router: Builds a router from a set of routes.
  - match: Attempts to match a router against a Ring-style request."
  (:require [clojure.string :as str])
  (:import (java.util HashMap)))

(set! *warn-on-reflection* true)

(deftype TrieNode [handler
                   ^HashMap static
                   ^objects patterns
                   param ; Param instance, typed after Param is defined
                   wildcard])

;; Frozen named-param child: keyword name + child TrieNode.
(deftype Param [name ^TrieNode child])

;; Frozen sub-segment pattern entry: parts vector + child TrieNode.
(deftype Pattern [^clojure.lang.IPersistentVector parts ^TrieNode child])

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
;; At the end of router, freeze-node converts this tree to TrieNode instances
;; for named field access at match time.

(def ^:private empty-node
  {:handler nil :static {} :patterns [] :param nil :wildcard nil})

(defn- freeze-node
  ^TrieNode [m]
  (TrieNode.
   (:handler m)
   (let [^HashMap hm (HashMap.)]
     (doseq [[k child] (:static m)]
       (.put hm k (freeze-node child)))
     hm)
   (when (seq (:patterns m))
     (let [pats (:patterns m)
           arr (object-array (count pats))]
       (doseq [[i [parts child]] (map-indexed vector pats)]
         (aset arr i (Pattern. parts (freeze-node child))))
       arr))
   (when-let [p (:param m)]
     (Param. (:name p) (freeze-node (:child p))))
   (:wildcard m)))

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

;; Walk the URI string directly without pre-splitting into segments.
;; pos = start of the current segment (character after the last '/').
;; Eliminates PersistentVector allocation and .nth dispatch on the hot path.
;; We use 4 args (the max supporting primitive hints) with ^long pos;
;; uri.length() is O(1) on the JVM (cached field) so calling it per frame is free.
(defn- match-node
  [^TrieNode node ^String uri ^long pos params]
  (let [len (.length uri)]
    (if (= pos len)
      ;; consumed all input: check for handler or wildcard at this node
      (or (when-let [h (.handler node)] [h params])
          (when-some [w (.wildcard node)] [w params]))
      ;; find end of current segment (next '/' or end of string)
      (let [slash (.indexOf uri (int \/) (int pos))
            seg-end (if (neg? slash) len slash)
            seg (.substring uri (int pos) (int seg-end))
            next-pos (inc seg-end)]
        (or
         ;; 1. static exact match
         (when-let [child (.get ^HashMap (.static node) seg)]
           (if (= seg-end len)
             (or (when-let [h (.handler ^TrieNode child)] [h params])
                 (when-some [w (.wildcard ^TrieNode child)] [w params]))
             (match-node ^TrieNode child uri next-pos params)))
         ;; 2. sub-segment patterns (tried in insertion order)
         (when-let [^objects pats (.patterns node)]
           (loop [i 0]
             (when (< i (alength pats))
               (let [^Pattern pat (aget pats i)]
                 (or (when-some [p (match-parts (.parts pat) seg params)]
                       (if (= seg-end len)
                         (or (when-let [h (.handler ^TrieNode (.child pat))] [h p])
                             (when-some [w (.wildcard ^TrieNode (.child pat))] [w p]))
                         (match-node (.child pat) uri next-pos p)))
                     (recur (inc i)))))))
         ;; 3. whole-segment named param
         (when-let [^Param p (.param node)]
           (let [params (assoc params (.name p) seg)]
             (if (= seg-end len)
               (or (when-let [h (.handler ^TrieNode (.child p))] [h params])
                   (when-some [w (.wildcard ^TrieNode (.child p))] [w params]))
               (match-node (.child p) uri next-pos params))))
         ;; 4. wildcard -- remainder starts at pos (current segment start)
         (when-some [w (.wildcard node)]
           [w (assoc params :* (.substring uri (int pos)))]))))))

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
      ;; fast path: all-static routes -- single URI lookup, no params
      (when-let [h (.get ^HashMap v uri)]
        [h {}])
      ;; trie path: walk URI string directly, no segment pre-splitting
      (let [^String uri uri
            ;; skip leading slash
            start (if (and (pos? (.length uri)) (= \/ (.charAt uri 0))) 1 0)]
        (match-node ^TrieNode v uri start {})))))
