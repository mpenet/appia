(ns s-exp.appia-test
  (:require
   [clojure.test :as test :refer [are deftest is testing]]
   [s-exp.appia :as router]))

;;; ----------------------------------------------------------------
;;; Helpers
;;; ----------------------------------------------------------------

(defn- m [matcher method uri]
  (router/match matcher {:request-method method :uri uri}))

;;; ----------------------------------------------------------------
;;; Basic routing
;;; ----------------------------------------------------------------

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

;;; ----------------------------------------------------------------
;;; No-match cases
;;; ----------------------------------------------------------------

(deftest test-no-match
  (let [routes {[:get "/users/{id}"] :user
                [:post "/users"] :create-user}
        matcher (router/router routes)]
    (testing "unknown method"
      (is (nil? (m matcher :delete "/users/42"))))
    (testing "unknown path"
      (is (nil? (m matcher :get "/nope"))))
    (testing "wrong method on known path"
      (is (nil? (m matcher :put "/users"))))
    (testing "path too deep with no wildcard"
      (is (nil? (m matcher :get "/users/42/extra"))))))

;;; ----------------------------------------------------------------
;;; Method dispatch
;;; ----------------------------------------------------------------

(deftest test-method-dispatch
  (let [routes {[:get "/r"] :get-r
                [:post "/r"] :post-r
                [:put "/r"] :put-r
                [:delete "/r"] :delete-r
                [:patch "/r"] :patch-r}
        matcher (router/router routes)]
    (are [method] (= [(keyword (str (name method) "-r")) {}]
                     (m matcher method "/r"))
      :get :post :put :delete :patch)
    (is (nil? (m matcher :options "/r")))))

;;; ----------------------------------------------------------------
;;; Static-only fast path (HashMap dispatch)
;;; ----------------------------------------------------------------

(deftest test-static-only
  (let [routes {[:get "/"] :root
                [:get "/login"] :login
                [:get "/logout"] :logout
                [:get "/about"] :about
                [:get "/health"] :health}
        matcher (router/router routes)]
    (testing "all static routes resolve correctly"
      (are [uri handler] (= [handler {}] (m matcher :get uri))
        "/" :root
        "/login" :login
        "/logout" :logout
        "/about" :about
        "/health" :health))
    (testing "static router is backed by HashMap"
      ;; Verify the static-only optimisation kicked in
      (is (instance? java.util.HashMap (get matcher :get))))
    (testing "no match returns nil"
      (is (nil? (m matcher :get "/missing"))))))

;;; ----------------------------------------------------------------
;;; Match priority: static > sub-segment > param > wildcard
;;; ----------------------------------------------------------------

(deftest test-match-priority
  (let [routes {[:get "/x/literal"] :static
                [:get "/x/{name}.json"] :sub-seg
                [:get "/x/{id}"] :param
                [:get "/x/*"] :wild}
        matcher (router/router routes)]
    (testing "literal beats everything"
      (is (= [:static {}] (m matcher :get "/x/literal"))))
    (testing "sub-segment beats param and wildcard"
      (is (= [:sub-seg {:name "foo"}] (m matcher :get "/x/foo.json"))))
    (testing "param beats wildcard"
      (is (= [:param {:id "anything"}] (m matcher :get "/x/anything"))))
    (testing "wildcard catches multi-segment remainder"
      (is (= [:wild {:* "a/b"}] (m matcher :get "/x/a/b"))))))

;;; ----------------------------------------------------------------
;;; Wildcards
;;; ----------------------------------------------------------------

(deftest test-wildcards
  (let [routes {[:get "/*"] :a
                [:get "/x/*"] :b}
        matcher (router/router routes)]
    (are [p key params] (= [key params] (router/match matcher {:request-method (first p)
                                                               :uri (second p)}))
      [:get "/"] :a {}
      [:get "/x"] :b {}
      [:get "/x/y"] :b {:* "y"})))

(deftest test-wildcard-zero-trailing
  (let [routes {[:get "/assets/*"] :assets}
        matcher (router/router routes)]
    (testing "wildcard with no trailing content"
      (is (= [:assets {}] (m matcher :get "/assets/"))))
    (testing "wildcard captures single segment"
      (is (= [:assets {:* "logo.png"}] (m matcher :get "/assets/logo.png"))))
    (testing "wildcard captures multiple segments"
      (is (= [:assets {:* "img/a/b.png"}] (m matcher :get "/assets/img/a/b.png"))))))

;;; ----------------------------------------------------------------
;;; Edge cases
;;; ----------------------------------------------------------------

(deftest test-edge-cases
  (let [routes {[:get "/a/{id}/b"] :ab
                [:get "/*"] :all}
        matcher (router/router routes)]
    (are [p key params] (= [key params] (router/match matcher {:request-method (first p)
                                                               :uri (second p)}))
      [:get "/a/{id}/b"] :ab {:id "{id}"})))

(deftest test-uri-without-leading-slash
  ;; match/start logic skips leading slash if present; without one it
  ;; should still work (start=0)
  (let [routes {[:get "/hello"] :hello}
        matcher (router/router routes)]
    (is (= [:hello {}] (m matcher :get "/hello")))))

(deftest test-handler-any-value
  (testing "handler can be a map"
    (let [matcher (router/router {[:get "/a"] {:k :v}})]
      (is (= [{:k :v} {}] (m matcher :get "/a")))))
  (testing "handler can be a number"
    (let [matcher (router/router {[:get "/a"] 42})]
      (is (= [42 {}] (m matcher :get "/a")))))
  (testing "handler can be false"
    ;; when-some vs when-let: wildcard uses when-some so false handlers work
    (let [matcher (router/router {[:get "/*"] false})]
      (is (= [false {:* "x"}] (m matcher :get "/x")))))
  (testing "handler can be a string"
    (let [matcher (router/router {[:get "/a"] "ok"})]
      (is (= ["ok" {}] (m matcher :get "/a"))))))

(deftest test-multiple-methods-same-path
  (let [matcher (router/router {[:get "/item/{id}"] :read
                                [:put "/item/{id}"] :update
                                [:delete "/item/{id}"] :delete})]
    (is (= [:read {:id "7"}] (m matcher :get "/item/7")))
    (is (= [:update {:id "7"}] (m matcher :put "/item/7")))
    (is (= [:delete {:id "7"}] (m matcher :delete "/item/7")))
    (is (nil? (m matcher :post "/item/7")))))

;;; ----------------------------------------------------------------
;;; Sub-segment params
;;; ----------------------------------------------------------------

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

(deftest test-sub-segment-no-match-falls-to-param
  ;; A segment that doesn't satisfy the sub-seg pattern should fall through
  ;; to the whole-segment param if one exists.
  (let [routes {[:get "/files/{name}.{ext}"] :file
                [:get "/files/{id}"] :file-id}
        matcher (router/router routes)]
    (testing "segment with dot matches sub-seg"
      (is (= [:file {:name "report" :ext "pdf"}] (m matcher :get "/files/report.pdf"))))
    (testing "segment without dot falls to param"
      (is (= [:file-id {:id "readme"}] (m matcher :get "/files/readme"))))))

(deftest test-sub-segment-urn-style
  (let [routes {[:get "/obj/urn:{type}:{id}"] :object}
        matcher (router/router routes)]
    (is (= [:object {:type "book" :id "42"}] (m matcher :get "/obj/urn:book:42")))
    (is (= [:object {:type "isbn" :id "978-3-16"}] (m matcher :get "/obj/urn:isbn:978-3-16")))))
