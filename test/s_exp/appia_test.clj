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
;;; Static-only routers
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

(deftest test-handler-any-value
  (testing "handler can be a map"
    (let [matcher (router/router {[:get "/a"] {:k :v}})]
      (is (= [{:k :v} {}] (m matcher :get "/a")))))
  (testing "handler can be a number"
    (let [matcher (router/router {[:get "/a"] 42})]
      (is (= [42 {}] (m matcher :get "/a")))))
  (testing "handler can be false"
    ;; only nil means no route
    (let [matcher (router/router {[:get "/*"] false})]
      (is (= [false {:* "x"}] (m matcher :get "/x"))))
    (testing "on a static-only router"
      (let [matcher (router/router {[:get "/a"] false})]
        (is (= [false {}] (m matcher :get "/a")))))
    (testing "on static and param routes of a trie router"
      (let [matcher (router/router {[:get "/a"] false
                                    [:get "/b/{id}"] false})]
        (is (= [false {}] (m matcher :get "/a")))
        (is (= [false {:id "1"}] (m matcher :get "/b/1"))))))
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

;;; ----------------------------------------------------------------
;;; Static children lookup
;;; ----------------------------------------------------------------

(deftest test-many-static-siblings
  (let [names (map #(str "r" %) (range 100))
        routes (into {[:get "/{id}"] :param
                      ;; "Aa" and "BB" share the same String hash
                      [:get "/Aa/{id}"] :aa
                      [:get "/BB/{id}"] :bb}
                     (map (fn [n] [[:get (str "/" n "/{id}")] (keyword n)]))
                     names)
        matcher (router/router routes)]
    (doseq [n names]
      (is (= [(keyword n) {:id "1"}] (m matcher :get (str "/" n "/1")))))
    (is (= [:aa {:id "1"}] (m matcher :get "/Aa/1")))
    (is (= [:bb {:id "1"}] (m matcher :get "/BB/1")))
    (is (= [:param {:id "r100"}] (m matcher :get "/r100")))
    (is (= [:param {:id "Ab"}] (m matcher :get "/Ab")))))

;;; ----------------------------------------------------------------
;;; Path params construction
;;; ----------------------------------------------------------------

(deftest test-params-after-backtracking
  ;; the sub-segment branch captures x/y then fails deeper; the param
  ;; branch must not leak them
  (let [matcher (router/router {[:get "/a/{x}.{y}/z"] :sub
                                [:get "/a/{p}/w"] :param})]
    (is (= [:param {:p "foo.bar"}] (m matcher :get "/a/foo.bar/w")))
    (is (= [:sub {:x "foo" :y "bar"}] (m matcher :get "/a/foo.bar/z")))))

(deftest test-duplicate-param-names
  (let [matcher (router/router {[:get "/{id}/x/{id}"] :dup})]
    (is (= [:dup {:id "2"}] (m matcher :get "/1/x/2")))))

(deftest test-many-params
  (let [path (apply str (map #(str "/{p" % "}") (range 10)))
        uri (apply str (map #(str "/v" %) (range 10)))
        matcher (router/router {[:get path] :many})]
    (is (= [:many (into {} (map (fn [i] [(keyword (str "p" i)) (str "v" i)])) (range 10))]
           (m matcher :get uri)))))

(deftest test-wildcard-with-params
  (let [matcher (router/router {[:get "/u/{id}/*"] :u})]
    (is (= [:u {:id "1" :* "a/b"}] (m matcher :get "/u/1/a/b")))
    (is (= [:u {:id "1"}] (m matcher :get "/u/1")))))

(deftest test-adjacent-params-rejected
  ;; "{a}{b}" has no delimiter to split on, so the route is ambiguous
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"adjacent params"
                        (router/router {[:get "/x/{a}{b}"] :x})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"adjacent params"
                        (router/router {[:get "/x/pre{a}{b}.txt"] :x}))))

(deftest test-method-keys-compare-by-value
  (let [matcher (router/router {["GET" "/a"] :a
                                ["GET" "/b/{id}"] :b})]
    (is (= [:a {}] (m matcher "GET" "/a")))
    (is (= [:b {:id "1"}] (m matcher "GET" "/b/1")))
    (is (nil? (m matcher :get "/a")))))

(deftest test-request-without-uri
  (testing "static-only router"
    (is (nil? (router/match (router/router {[:get "/a"] :a}) {:request-method :get}))))
  (testing "trie router"
    (is (nil? (router/match (router/router {[:get "/a/{id}"] :a}) {:request-method :get})))))

;;; ----------------------------------------------------------------
;;; Trailing slashes and empty segments: matched strictly
;;; ----------------------------------------------------------------

(deftest test-trailing-slash-strict
  (testing "trie router"
    (let [matcher (router/router {[:get "/login"] :login
                                  [:get "/users/{id}"] :user
                                  [:get "/users/"] :users-slash})]
      (is (= [:login {}] (m matcher :get "/login")))
      (is (nil? (m matcher :get "/login/")))
      (is (= [:user {:id "1"}] (m matcher :get "/users/1")))
      (is (nil? (m matcher :get "/users/1/")))
      (is (= [:users-slash {}] (m matcher :get "/users/")))
      (is (nil? (m matcher :get "/users")))))
  (testing "static-only router"
    (let [matcher (router/router {[:get "/a"] :a
                                  [:get "/b/"] :b})]
      (is (nil? (m matcher :get "/a/")))
      (is (= [:b {}] (m matcher :get "/b/")))
      (is (nil? (m matcher :get "/b"))))))

(deftest test-param-never-matches-empty-segment
  (let [matcher (router/router {[:get "/users/{id}"] :user})]
    (is (nil? (m matcher :get "/users/")))))

(deftest test-empty-segments-strict
  (let [matcher (router/router {[:get "/a//b"] :double
                                [:get "/x/{id}"] :x})]
    (is (= [:double {}] (m matcher :get "/a//b")))
    (is (nil? (m matcher :get "/a/b")))
    (is (nil? (m matcher :get "/x//")))))

(deftest test-leading-slash-required
  (testing "uri"
    (let [matcher (router/router {[:get "/hello"] :hello
                                  [:get "/x/{id}"] :x})]
      (is (nil? (m matcher :get "hello")))
      (is (nil? (m matcher :get "x/1")))))
  (testing "route path"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must start with /"
                          (router/router {[:get "hello"] :hello})))))

(deftest test-sub-segment-final-literal-ends-segment
  ;; a literal ending the pattern matches at the end of the segment, even when
  ;; it also occurs earlier in it
  (let [matcher (router/router {[:get "/f/{name}.json"] :json
                                [:get "/g/{a}-{b}.tar.gz"] :tgz})]
    (is (= [:json {:name "a.json"}] (m matcher :get "/f/a.json.json")))
    (is (= [:tgz {:a "x" :b "y.tar.gz"}] (m matcher :get "/g/x-y.tar.gz.tar.gz")))
    (is (nil? (m matcher :get "/f/a.jsonx")))))

(deftest test-invalid-route-keys-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[method path\]"
                        (router/router {:get :x})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[method path\]"
                        (router/router {[:get :not-a-path] :x}))))

(deftest test-sub-segment-values-never-empty
  (let [matcher (router/router {[:get "/g/{name}.{ext}"] :ext
                                [:get "/v/v{major}"] :v
                                [:get "/d/{a}-{b}"] :dash})]
    (is (nil? (m matcher :get "/g/readme.")))
    (is (nil? (m matcher :get "/g/.bashrc")))
    (is (nil? (m matcher :get "/v/v")))
    (is (= [:dash {:a "-x" :b "y"}] (m matcher :get "/d/-x-y")))))

(deftest test-sub-segment-most-specific-first
  ;; tried by most literal characters, whatever the route order
  (doseq [routes [(array-map [:get "/f/{name}.{ext}"] :ext [:get "/f/{name}.json"] :json)
                  (array-map [:get "/f/{name}.json"] :json [:get "/f/{name}.{ext}"] :ext)]]
    (let [matcher (router/router routes)]
      (is (= [:json {:name "data.json"}] (m matcher :get "/f/data.json.json")))
      (is (= [:json {:name "a"}] (m matcher :get "/f/a.json")))
      (is (= [:ext {:name "a" :ext "txt"}] (m matcher :get "/f/a.txt"))))))

(deftest test-java-match-by-method-and-uri
  (let [^s_exp.appia.Router matcher (router/router {[:get "/a"] :a
                                                    [:get "/b/{id}"] :b})]
    (is (= [:a {}] (.match matcher :get "/a")))
    (is (= [:b {:id "1"}] (.match matcher :get "/b/1")))
    (is (nil? (.match matcher :post "/a")))
    (is (nil? (.match matcher :get nil)))))

;;; ----------------------------------------------------------------
;;; Path params behave as a regular map
;;; ----------------------------------------------------------------

(deftest test-path-params-behave-as-map
  (let [matcher (router/router {[:get "/a/{x}/b/{y}"] :ab
                                [:get "/f/{name}.{ext}"] :f
                                [:get "/w/{id}/*"] :w})]
    (doseq [[uri expected] [["/a/1/b/2" {:x "1" :y "2"}]
                            ["/f/report.pdf" {:name "report" :ext "pdf"}]
                            ["/w/7/c/d" {:id "7" :* "c/d"}]]]
      (let [[_ p] (m matcher :get uri)]
        (testing uri
          (is (map? p))
          (is (= expected p))
          (is (= p expected))
          (is (= (hash expected) (hash p)))
          (is (.equals ^Object expected p))
          (is (.equals ^Object p expected))
          (is (= (.hashCode ^Object expected) (.hashCode ^Object p)))
          (is (= (count expected) (count p)))
          (is (= (set (seq expected)) (set (seq p))))
          (is (= (set (keys expected)) (set (keys p))))
          (is (= (set (vals expected)) (set (vals p))))
          (doseq [[k v] expected]
            (is (= v (get p k)))
            (is (= v (p k)))
            (is (= v (k p)))
            (is (contains? p k))
            (is (= [k v] (find p k))))
          (is (nil? (get p :missing)))
          (is (= ::nf (get p :missing ::nf)))
          (is (not (contains? p :missing)))
          (is (nil? (find p :missing)))
          (is (= (assoc expected :z "z") (assoc p :z "z")))
          (is (= (dissoc expected (key (first expected))) (dissoc p (key (first expected)))))
          (is (= (merge expected {:z 1}) (merge p {:z 1})))
          (is (= (merge {:z 1} expected) (merge {:z 1} p)))
          (is (= (into {} expected) (into {} p)))
          (is (= (conj expected [:z 1]) (conj p [:z 1])))
          (is (= (select-keys expected (keys expected)) (select-keys p (keys expected))))
          (is (= (reduce-kv assoc {} expected) (reduce-kv assoc {} p)))
          (is (= (into {} (map (fn [[k v]] [k v])) expected) (into {} p)))
          (is (= {} (empty p)))
          (is (nil? (meta p)))
          (is (= {:m 1} (meta (with-meta p {:m 1}))))
          (is (= expected (with-meta p {:m 1})))
          (is (= expected (persistent! (transient p))))
          (is (= expected (read-string (pr-str p))))
          (is (= expected (java.util.HashMap. ^java.util.Map p))))))))

(deftest test-path-params-serialize-as-map
  (let [[_ p] (m (router/router {[:get "/a/{x}"] :a}) :get "/a/1")
        bytes (java.io.ByteArrayOutputStream.)]
    (with-open [out (java.io.ObjectOutputStream. bytes)]
      (.writeObject out p))
    (with-open [in (java.io.ObjectInputStream. (java.io.ByteArrayInputStream. (.toByteArray bytes)))]
      (let [copy (.readObject in)]
        (is (instance? clojure.lang.PersistentArrayMap copy))
        (is (= {:x "1"} copy))))))

(deftest test-static-routes-of-any-length
  (let [long-path (str "/" (apply str (repeat 70 "x")))
        paths (map #(str "/" (apply str (repeat % "a"))) [0 1 62 63 64 65 100])]
    (doseq [routes [(into {[:get long-path] :long} (map (fn [p] [[:get p] p])) paths)
                    (into {[:get long-path] :long [:get "/d/{id}"] :d}
                          (map (fn [p] [[:get p] p]))
                          paths)]]
      (let [matcher (router/router routes)]
        (is (= [:long {}] (m matcher :get long-path)))
        (doseq [p paths]
          (is (= [p {}] (m matcher :get p))))
        (is (nil? (m matcher :get (str long-path "y"))))))))
