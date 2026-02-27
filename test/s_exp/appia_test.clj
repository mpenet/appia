(ns s-exp.appia-test
  (:require
   [clojure.test :as test :refer [are deftest]]
   [s-exp.appia :as router]))

(deftest test-basics
  (let [routes {[:get "/"] :get-index
                [:get "/map"] {:something :else}
                [:get "/login"] :get-login
                [:post "/login"] :post-login
                [:get "/article/{id}"] :get-article
                [:get "/article/{id}/update"] :get-article-any-update
                [:get "/article/{id}/update/{thing}"] :get-article-any-update-thing
                [:get "/{id}"] :get-any
                [:get "/*"] :get-all}
        matcher (router/router routes)]
    (are [p key params] (= [key params] (router/match matcher {:request-method (first p)
                                                               :uri (second p)}))
      [:get "/"] :get-index {}
      [:get "/map"] {:something :else} {}
      [:get "/login"] :get-login {}
      [:post "/login"] :post-login {}
      [:get "/article/123"] :get-article {:id "123"}
      [:get "/article"] :get-any {:id "article"}
      [:get "/article/123/update"] :get-article-any-update {:id "123"}
      [:get "/article/123/update/thing"] :get-article-any-update-thing {:id "123" :thing "thing"}
      [:get "/any"] :get-any {:id "any"}
      [:get "/any/other"] :get-all {:* "any/other"})))

(deftest test-wildcards
  (let [routes {[:get "/*"] :a
                [:get "/x/*"] :b}
        matcher (router/router routes)]
    (are [p key params] (= [key params] (router/match matcher {:request-method (first p)
                                                               :uri (second p)}))
      [:get "/"] :a {}
      [:get "/x"] :b {}
      [:get "/x/y"] :b {:* "y"})))

(deftest test-edge-cases
  (let [routes {[:get "/a/{id}/b"] :ab
                [:get "/*"] :all}
        matcher (router/router routes)]
    (are [p key params] (= [key params] (router/match matcher {:request-method (first p)
                                                               :uri (second p)}))
      [:get "/a/{id}/b"] :ab {:id "{id}"})))

(deftest test-sub-segment-params
  (let [routes {[:get "/files/{name}.{ext}"] :file
                [:get "/prefix/{a}-{b}"] :dash
                [:get "/x/baz:{id}/end"] :baz-suffix
                [:get "/x/baz:literal/end"] :baz-literal
                [:get "/multi/{a}:{b}-{c}"] :multi}
        matcher (router/router routes)]
    (are [p key params] (= [key params] (router/match matcher {:request-method (first p)
                                                               :uri (second p)}))
      ;; sub-segment: suffix param
      [:get "/files/readme.txt"] :file {:name "readme" :ext "txt"}
      ;; sub-segment: middle param
      [:get "/prefix/foo-bar"] :dash {:a "foo" :b "bar"}
      ;; sub-segment: prefix literal, suffix param
      [:get "/x/baz:42/end"] :baz-suffix {:id "42"}
      ;; literal beats sub-segment pattern
      [:get "/x/baz:literal/end"] :baz-literal {}
      ;; multiple params in one segment
      [:get "/multi/foo:bar-baz"] :multi {:a "foo" :b "bar" :c "baz"})))
