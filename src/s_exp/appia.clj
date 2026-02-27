(ns s-exp.appia
  "Router utilities for HTTP request handling using matching on path/method.

  Provides functions for building and matching routes, adapted from
  clj-simple-router but modified to match on named path parameters
  (e.g. '/foo/{bar}') and HTTP methods instead of simple wildcards.

  Exposes:
  - make-matcher: Builds a matcher from a set of routes.
  - match: Attempts to match a matcher against a Ring-style request."
  (:require [clojure.string :as str])
  (:import (java.util.regex Pattern)))

;; Adapted/Modified from https://github.com/tonsky/clj-simple-router/tree/main
;;
;; Copyright 2023 Nikita Prokopov - Licensed under MIT License.

(set! *warn-on-reflection* true)

(defn- compare-masks
  [as bs]
  (let [a (first as)
        b (first bs)]
    (cond
      (= nil as bs) 0
      (= nil as) -1
      (= nil bs) 1
      (= "*" a b) (recur (next as) (next bs))
      (= "*" a) 1
      (= "*" b) -1
      (and (keyword? a) (keyword? b))
      (recur (next as) (next bs))
      (keyword? a) 1
      (keyword? b) -1
      ;; mixed sub-segment pattern ranks between literal and full-param
      (and (map? a) (map? b)) (recur (next as) (next bs))
      (map? a) 1
      (map? b) -1
      :else (recur (next as) (next bs)))))

(def ^:private param-re #"\{([^}]+)\}")

(defn- parse-segment-pattern
  [segment]
  (let [whole-match (re-matches param-re segment)]
    (if whole-match
      ;; whole-segment param
      (keyword (second whole-match))
      ;; check for any {param} occurrences in segment
      (let [params (mapv (comp keyword second) (re-seq param-re segment))]
        (if (empty? params)
          ;; pure literal
          segment
          ;; mixed: build a regex replacing each {param} with a capture group,
          ;; escaping literal portions
          (let [pattern-str (loop [s segment
                                   result "^"]
                              (let [m (re-find param-re s)]
                                (if (nil? m)
                                  (str result (Pattern/quote s) "$")
                                  (let [token (first m)
                                        match-start (.indexOf ^String s ^String token)
                                        literal-part (subs s 0 match-start)
                                        rest-s (subs s (+ match-start (count token)))]
                                    (recur rest-s
                                           (str result
                                                (Pattern/quote literal-part)
                                                "([^/]+)"))))))]
            {:pattern (re-pattern pattern-str)
             :params params}))))))

(defn- split-uri
  [path]
  (filter #(not= "" %) (str/split path #"/+")))

(defn- split-route
  [path]
  (map parse-segment-pattern (split-uri path)))

(defn- matches?
  [mask path]
  (loop [mask mask
         path path
         params {}]
    (let [m (first mask)
          p (first path)]
      (cond
        (= "*" m) (if (seq path)
                    (assoc params :* (str/join "/" path))
                    params)
        (= nil mask path) params
        (= nil mask) nil
        (= nil path) nil
        (keyword? m)
        (recur (next mask) (next path) (assoc params m p))
        (map? m)
        (when-some [groups (some->> p (re-matches (:pattern m)) rest seq)]
          (recur (next mask) (next path)
                 (into params (map vector (:params m) groups))))
        (= m p)
        (recur (next mask) (next path) params)))))

(defn- match*
  [matcher path]
  (reduce (fn [_ [mask v]]
            (when-some [params (matches? mask path)]
              (reduced [v params])))
          nil
          matcher))

(defn router
  "Given set of routes, builds router"
  [routes]
  (-> (reduce (fn [routes [[method & [path]] key]]
                (update routes method conj
                        [(split-route path) key]))
              {}
              routes)
      (update-vals #(sort-by first compare-masks %))))

(defn match
  "Given `matcher` attempts to match against ring request, return match (tuple of
  `data` & `path-params`)"
  [matcher {:as _request :keys [request-method uri]}]
  (when-let [m (get matcher request-method)]
    (match* m (split-uri uri))))
