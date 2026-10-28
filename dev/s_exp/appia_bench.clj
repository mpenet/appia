(ns s-exp.appia-bench
  "Benchmarks comparing appia against reitit-core and pedestal.route.

  Every measurement calls the matcher round-robin over a request set, keeping
  results alive, and reports the median ns/op of many short timed runs. Per-request
  numbers are taken after warming up on the whole set, so the JIT has seen
  every route shape: warming a single route lets it specialize for that shape
  and deoptimize when the next route takes other branches.

  Each timed run matches fresh copies of the requests, built just before it,
  whose URI strings have no cached hash yet, as with real requests. Runs are
  kept short so the copies stay in cache, as a request just parsed by a server
  would be.

  Run with reitit (requires :bench/reitit alias):
    clj -M:bench:bench/reitit -e \"(require 's-exp.appia-bench)(s-exp.appia-bench/run-reitit)\"

  Run with pedestal (requires :bench/pedestal alias):
    clj -M:bench:bench/pedestal -e \"(require 's-exp.appia-bench)(s-exp.appia-bench/run-pedestal)\"

  Run both:
    clj -M:bench:bench/reitit:bench/pedestal -e \"(require 's-exp.appia-bench)(s-exp.appia-bench/run-all)\"")

(set! *warn-on-reflection* true)

;;; ----------------------------------------------------------------
;;; Helpers
;;; ----------------------------------------------------------------

(def ^:private ^"[Ljava.lang.Object;" sink (object-array 1))

(def ^:private pool-size 50000)

(def ^:private timed-runs 60)

(defn- fresh-copy
  "Copy of a uri string or request map with a new uri string, sharing the
  characters but with no cached hash."
  [x]
  (if (string? x)
    (String. ^String x)
    (let [uri (String. ^String (:uri x))]
      (cond-> (assoc x :uri uri)
        (contains? x :path-info) (assoc :path-info uri)))))

(defn- fresh-pool
  "pool-size fresh copies of inputs, round-robin."
  ^objects [inputs]
  (let [inputs (vec inputs)
        k (count inputs)
        pool (object-array pool-size)]
    (dotimes [i pool-size]
      (aset pool i (fresh-copy (nth inputs (rem i k)))))
    pool))

(defn- run-pool
  [^clojure.lang.IFn f ^objects pool]
  (dotimes [i (alength pool)]
    (aset sink 0 (.invoke f (aget pool i)))))

(defn- ns-per-op
  "Median ns/op of timed runs of f, each over a fresh pool of inputs."
  [f inputs]
  (run-pool f (fresh-pool inputs))
  (let [times (sort (for [_ (range timed-runs)]
                      (let [pool (fresh-pool inputs)
                            t0 (System/nanoTime)]
                        (run-pool f pool)
                        (/ (- (System/nanoTime) t0) (double pool-size)))))]
    (nth times (quot timed-runs 2))))

(defn- b
  "Prints label and ns/op of f over inputs."
  [label f inputs]
  (println (format "  %-44s %8.1f ns" label (ns-per-op f inputs))))

(defn- reading
  "f followed by reading each of the param values a handler would use:
  input->keys gives the param keys of an input, match->params the params of
  a match."
  [^clojure.lang.IFn f input->keys match->params]
  (fn [input]
    (let [m (.invoke f input)
          params (match->params m)
          ^clojure.lang.Indexed ks (input->keys input)]
      (dotimes [i (.count ^clojure.lang.Counted ks)]
        (aset sink 0 (get params (.nth ks i))))
      m)))

(defn- warm-up
  "Runs f round-robin over inputs long enough for the JIT to settle."
  [f inputs]
  (dotimes [_ 400]
    (run-pool f (fresh-pool inputs))))

;;; ----------------------------------------------------------------
;;; Route sets and requests
;;; ----------------------------------------------------------------

(def mixed-appia-routes
  {[:get "/"] :root
   [:get "/login"] :login
   [:get "/map"] :map
   [:get "/article/{id}"] :article
   [:get "/article/{id}/update"] :article-update
   [:get "/article/{id}/update/{thing}"] :article-update-thing
   [:get "/files/{name}"] :files})

(def static-appia-routes
  {[:get "/"] :root
   [:get "/login"] :login
   [:get "/logout"] :logout
   [:get "/about"] :about
   [:get "/health"] :health})

(def sub-seg-appia-routes
  (merge mixed-appia-routes
         {[:get "/files/{name}.{ext}"] :file-ext
          [:get "/prefix/{a}-{b}"] :dash
          [:get "/obj/urn:{type}:{id}"] :object}))

(def mixed-param-keys
  "Param keys of each mixed route, by uri."
  {"/" []
   "/login" []
   "/map" []
   "/article/123" [:id]
   "/article/123/update" [:id]
   "/article/123/update/thing" [:id :thing]
   "/files/readme" [:name]})

(def mixed-reqs
  [{:request-method :get :uri "/"}
   {:request-method :get :uri "/login"}
   {:request-method :get :uri "/map"}
   {:request-method :get :uri "/article/123"}
   {:request-method :get :uri "/article/123/update"}
   {:request-method :get :uri "/article/123/update/thing"}
   {:request-method :get :uri "/files/readme"}])

(def static-reqs
  [{:request-method :get :uri "/"}
   {:request-method :get :uri "/login"}
   {:request-method :get :uri "/logout"}
   {:request-method :get :uri "/about"}
   {:request-method :get :uri "/health"}])

(def sub-seg-reqs
  [{:request-method :get :uri "/files/report.pdf"}
   {:request-method :get :uri "/prefix/foo-bar"}
   {:request-method :get :uri "/obj/urn:book:42"}])

(defn- appia-matcher
  "Returns a fn of request matching against an appia router built from routes."
  [routes]
  (require 's-exp.appia)
  (let [match @(ns-resolve 's-exp.appia 'match)
        r (@(ns-resolve 's-exp.appia 'router) routes)]
    (fn [req] (match r req))))

(defn- appia-path-matcher
  "Returns a fn of uri matching :get requests against an appia router built
  from routes, skipping the request map lookups: the same work as reitit's
  match-by-path, plus method dispatch."
  [routes]
  (require 's-exp.appia)
  (let [^s_exp.appia.Router r (@(ns-resolve 's-exp.appia 'router) routes)]
    (fn [^String uri] (.match r :get uri))))

;;; ----------------------------------------------------------------
;;; reitit
;;; ----------------------------------------------------------------

(defn bench-reitit []
  (require 'reitit.core)
  (let [match-by-path @(ns-resolve 'reitit.core 'match-by-path)
        reitit-routes [["/"] ["/login"] ["/map"]
                       ["/article/:id"] ["/article/:id/update"]
                       ["/article/:id/update/:thing"] ["/files/:name"]]
        reitit-r (@(ns-resolve 'reitit.core 'router) reitit-routes {:conflicts nil})
        reitit (fn [uri] (match-by-path reitit-r uri))
        appia (appia-matcher mixed-appia-routes)
        appia-path (appia-path-matcher mixed-appia-routes)
        uris (mapv :uri mixed-reqs)]
    (warm-up reitit uris)
    (warm-up appia mixed-reqs)
    (warm-up appia-path uris)
    (println "\n=== Mixed routes — round-robin (7 reqs) ===")
    (b "reitit-core (path)" reitit uris)
    (b "appia (method + path)" appia-path uris)
    (b "appia (request map)" appia mixed-reqs)
    (println "\n=== Mixed routes — per request ===")
    (doseq [req mixed-reqs]
      (let [uri (:uri req)]
        (b (str "reitit (path) " uri) reitit [uri])
        (b (str "appia (method + path) " uri) appia-path [uri])
        (b (str "appia (request map) " uri) appia [req])))
    ;; a handler reads its params; appia only builds the values that are read
    (let [uri-keys #(mixed-param-keys %)
          req-keys #(mixed-param-keys (:uri %))
          reitit-reading (reading reitit uri-keys :path-params)
          appia-path-reading (reading appia-path uri-keys val)
          appia-reading (reading appia req-keys val)]
      (warm-up reitit-reading uris)
      (warm-up appia-path-reading uris)
      (warm-up appia-reading mixed-reqs)
      (println "\n=== Mixed routes, reading params — round-robin (7 reqs) ===")
      (b "reitit-core (path)" reitit-reading uris)
      (b "appia (method + path)" appia-path-reading uris)
      (b "appia (request map)" appia-reading mixed-reqs)
      (println "\n=== Mixed routes, reading params — per request ===")
      (doseq [req mixed-reqs
              :let [uri (:uri req)]
              :when (seq (mixed-param-keys uri))]
        (b (str "reitit (path) " uri) reitit-reading [uri])
        (b (str "appia (method + path) " uri) appia-path-reading [uri])
        (b (str "appia (request map) " uri) appia-reading [req])))))

;;; ----------------------------------------------------------------
;;; pedestal
;;; ----------------------------------------------------------------

(defn bench-pedestal []
  (require 'io.pedestal.http.route
           'io.pedestal.http.route.prefix-tree
           'io.pedestal.http.route.map-tree)
  (let [h (fn [r] r)
        expand @(ns-resolve 'io.pedestal.http.route 'expand-routes)
        pt-router @(ns-resolve 'io.pedestal.http.route.prefix-tree 'router)
        mt-router @(ns-resolve 'io.pedestal.http.route.map-tree 'router)
        mixed-table
        (expand #{["/" :get h :route-name :root]
                  ["/login" :get h :route-name :login]
                  ["/map" :get h :route-name :map]
                  ["/article/:id" :get h :route-name :article]
                  ["/article/:id/update" :get h :route-name :article-update]
                  ["/article/:id/update/:thing" :get h :route-name :article-update-thing]
                  ["/files/:name" :get h :route-name :files]})
        static-table
        (expand #{["/" :get h :route-name :root]
                  ["/login" :get h :route-name :login]
                  ["/logout" :get h :route-name :logout]
                  ["/about" :get h :route-name :about]
                  ["/health" :get h :route-name :health]})
        ped-pt-mixed (pt-router mixed-table)
        ped-mt-mixed (mt-router mixed-table)
        ped-pt-static (pt-router static-table)
        ped-mt-static (mt-router static-table)
        appia-mixed (appia-matcher mixed-appia-routes)
        appia-static (appia-matcher static-appia-routes)
        ped-reqs (mapv #(assoc % :path-info (:uri %)) mixed-reqs)
        ped-static-reqs (mapv #(assoc % :path-info (:uri %)) static-reqs)]
    (warm-up ped-pt-mixed ped-reqs)
    (warm-up ped-mt-mixed ped-reqs)
    (warm-up appia-mixed mixed-reqs)
    (println "\n=== Mixed routes — round-robin (7 reqs) ===")
    (b "pedestal prefix-tree" ped-pt-mixed ped-reqs)
    (b "pedestal map-tree" ped-mt-mixed ped-reqs)
    (b "appia" appia-mixed mixed-reqs)
    (println "\n=== Mixed routes — per request ===")
    (doseq [req ped-reqs]
      (let [uri (:uri req)
            app-req (dissoc req :path-info)]
        (b (str "pedestal pt " uri) ped-pt-mixed [req])
        (b (str "pedestal mt " uri) ped-mt-mixed [req])
        (b (str "appia       " uri) appia-mixed [app-req])))
    (println "\n=== Static-only routes — round-robin (5 reqs) ===")
    (b "pedestal prefix-tree" ped-pt-static ped-static-reqs)
    (b "pedestal map-tree" ped-mt-static ped-static-reqs)
    (b "appia" appia-static static-reqs)))

;;; ----------------------------------------------------------------
;;; sub-segment params (appia only)
;;; ----------------------------------------------------------------

(defn bench-sub-segment []
  (let [appia (appia-matcher sub-seg-appia-routes)
        reqs (into mixed-reqs sub-seg-reqs)]
    (warm-up appia reqs)
    (println "\n=== Mixed + sub-segment routes — round-robin (appia only) ===")
    (b "appia" appia reqs)
    (println "\n=== Sub-segment params — per request (appia only) ===")
    (doseq [req sub-seg-reqs]
      (b (str "appia " (:uri req)) appia [req]))))

;;; ----------------------------------------------------------------
;;; Entry points
;;; ----------------------------------------------------------------

(defn run-reitit [& _]
  (println (str "appia vs reitit-core"
                "\nJVM: " (System/getProperty "java.version")
                "  Clojure: " (clojure-version)))
  (bench-reitit)
  (bench-sub-segment))

(defn run-pedestal [& _]
  (println (str "appia vs pedestal.route"
                "\nJVM: " (System/getProperty "java.version")
                "  Clojure: " (clojure-version)))
  (bench-pedestal)
  (bench-sub-segment))

(defn run-all [& _]
  (println (str "appia benchmarks"
                "\nJVM: " (System/getProperty "java.version")
                "  Clojure: " (clojure-version)))
  (bench-reitit)
  (bench-pedestal)
  (bench-sub-segment))
