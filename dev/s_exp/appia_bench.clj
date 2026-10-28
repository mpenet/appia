(ns s-exp.appia-bench
  "Benchmarks comparing appia against reitit-core and pedestal.route.

  Every measurement calls the matcher round-robin over a request set, keeping
  results alive, and reports the best ns/op of several timed runs. Per-request
  numbers are taken after warming up on the whole set, so the JIT has seen
  every route shape: warming a single route lets it specialize for that shape
  and deoptimize when the next route takes other branches.

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

(defn- run-round-robin
  [^clojure.lang.IFn f ^objects inputs ^long n]
  (let [k (alength inputs)]
    (dotimes [i n]
      (aset sink 0 (.invoke f (aget inputs (rem i k)))))))

(defn- ns-per-op
  "Best ns/op of 3 timed runs of f called round-robin over inputs."
  [f inputs]
  (let [inputs (object-array inputs)
        n 10000000]
    (run-round-robin f inputs n)
    (apply min (for [_ (range 3)]
                 (let [t0 (System/nanoTime)]
                   (run-round-robin f inputs n)
                   (/ (- (System/nanoTime) t0) (double n)))))))

(defn- b
  "Prints label and ns/op of f over inputs."
  [label f inputs]
  (println (format "  %-44s %8.1f ns" label (ns-per-op f inputs))))

(defn- warm-up
  "Runs f round-robin over inputs long enough for the JIT to settle."
  [f inputs]
  (run-round-robin f (object-array inputs) 50000000))

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
        uris (mapv :uri mixed-reqs)]
    (warm-up reitit uris)
    (warm-up appia mixed-reqs)
    (println "\n=== Mixed routes — round-robin (7 reqs) ===")
    (b "reitit-core" reitit uris)
    (b "appia" appia mixed-reqs)
    (println "\n=== Mixed routes — per request ===")
    (doseq [req mixed-reqs]
      (let [uri (:uri req)]
        (b (str "reitit " uri) reitit [uri])
        (b (str "appia  " uri) appia [req])))))

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
