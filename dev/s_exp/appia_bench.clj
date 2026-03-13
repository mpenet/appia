(ns s-exp.appia-bench
  "Benchmarks comparing appia against reitit-core and pedestal.route.

  Run with reitit (requires :bench/reitit alias):
    clj -M:bench:bench/reitit -e \"(require 's-exp.appia-bench)(s-exp.appia-bench/run-reitit)\"

  Run with pedestal (requires :bench/pedestal alias):
    clj -M:bench:bench/pedestal -e \"(require 's-exp.appia-bench)(s-exp.appia-bench/run-pedestal)\"

  Run both:
    clj -M:bench:bench/reitit:bench/pedestal -e \"(require 's-exp.appia-bench)(s-exp.appia-bench/run-all)\""
  (:require [criterium.core :as c]))

;;; ----------------------------------------------------------------
;;; Helpers
;;; ----------------------------------------------------------------

(defmacro b
  "Runs criterium quick-benchmark on f, prints label and mean in ns."
  [label f]
  `(let [result# (c/quick-benchmark* ~f {})]
     (println (format "  %-44s %8.1f ns" ~label (* 1e9 (first (:mean result#)))))))

;;; ----------------------------------------------------------------
;;; Route sets and requests
;;; ----------------------------------------------------------------

(def mixed-appia-routes
  {[:get "/"]                            :root
   [:get "/login"]                       :login
   [:get "/map"]                         :map
   [:get "/article/{id}"]                :article
   [:get "/article/{id}/update"]         :article-update
   [:get "/article/{id}/update/{thing}"] :article-update-thing
   [:get "/files/{name}"]                :files})

(def static-appia-routes
  {[:get "/"]       :root
   [:get "/login"]  :login
   [:get "/logout"] :logout
   [:get "/about"]  :about
   [:get "/health"] :health})

(def sub-seg-appia-routes
  (merge mixed-appia-routes
         {[:get "/files/{name}.{ext}"]  :file-ext
          [:get "/prefix/{a}-{b}"]      :dash
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

;;; ----------------------------------------------------------------
;;; reitit
;;; ----------------------------------------------------------------

(defn bench-reitit []
  (require '[reitit.core :as r])
  (require '[s-exp.appia :as appia])
  (let [match-fn (ns-resolve (find-ns 'reitit.core) 'match-by-path)
        reitit-routes [["/"] ["/login"] ["/map"]
                       ["/article/:id"] ["/article/:id/update"]
                       ["/article/:id/update/:thing"] ["/files/:name"]]
        reitit-r ((ns-resolve (find-ns 'reitit.core) 'router) reitit-routes {:conflicts nil})
        appia-match (ns-resolve (find-ns 's-exp.appia) 'match)
        appia-r ((ns-resolve (find-ns 's-exp.appia) 'router) mixed-appia-routes)]
    (println "\n=== Mixed routes — aggregate (7 reqs/iter) ===")
    (b "reitit-core" #(doseq [r mixed-reqs] (match-fn reitit-r (:uri r))))
    (b "appia"       #(doseq [r mixed-reqs] (appia-match appia-r r)))
    (println "\n=== Mixed routes — per request ===")
    (doseq [req mixed-reqs]
      (let [uri (:uri req)]
        (b (str "reitit " uri) #(match-fn reitit-r uri))
        (b (str "appia  " uri) #(appia-match appia-r req))))))

;;; ----------------------------------------------------------------
;;; pedestal
;;; ----------------------------------------------------------------

(defn bench-pedestal []
  (require '[io.pedestal.http.route :as route])
  (require '[io.pedestal.http.route.prefix-tree :as pt])
  (require '[io.pedestal.http.route.map-tree :as mt])
  (require '[s-exp.appia :as appia])
  (let [h            (fn [r] r)
        expand       (ns-resolve (find-ns 'io.pedestal.http.route) 'expand-routes)
        pt-router    (ns-resolve (find-ns 'io.pedestal.http.route.prefix-tree) 'router)
        mt-router    (ns-resolve (find-ns 'io.pedestal.http.route.map-tree) 'router)
        appia-match  (ns-resolve (find-ns 's-exp.appia) 'match)
        appia-router (ns-resolve (find-ns 's-exp.appia) 'router)
        mixed-table
        (expand #{["/"                          :get h :route-name :root]
                  ["/login"                     :get h :route-name :login]
                  ["/map"                       :get h :route-name :map]
                  ["/article/:id"               :get h :route-name :article]
                  ["/article/:id/update"        :get h :route-name :article-update]
                  ["/article/:id/update/:thing" :get h :route-name :article-update-thing]
                  ["/files/:name"               :get h :route-name :files]})
        static-table
        (expand #{["/"        :get h :route-name :root]
                  ["/login"   :get h :route-name :login]
                  ["/logout"  :get h :route-name :logout]
                  ["/about"   :get h :route-name :about]
                  ["/health"  :get h :route-name :health]})
        ped-pt-mixed    (pt-router mixed-table)
        ped-mt-mixed    (mt-router mixed-table)
        ped-pt-static   (pt-router static-table)
        ped-mt-static   (mt-router static-table)
        appia-mixed     (appia-router mixed-appia-routes)
        appia-static    (appia-router static-appia-routes)
        ped-reqs        (mapv #(assoc % :path-info (:uri %)) mixed-reqs)
        ped-static-reqs (mapv #(assoc % :path-info (:uri %)) static-reqs)]
    (println "\n=== Mixed routes — aggregate (7 reqs/iter) ===")
    (b "pedestal prefix-tree" #(doseq [r ped-reqs] (ped-pt-mixed r)))
    (b "pedestal map-tree"    #(doseq [r ped-reqs] (ped-mt-mixed r)))
    (b "appia"                #(doseq [r mixed-reqs] (appia-match appia-mixed r)))
    (println "\n=== Mixed routes — per request ===")
    (doseq [req ped-reqs]
      (let [uri     (:uri req)
            app-req (dissoc req :path-info)]
        (b (str "pedestal pt " uri) #(ped-pt-mixed req))
        (b (str "pedestal mt " uri) #(ped-mt-mixed req))
        (b (str "appia       " uri) #(appia-match appia-mixed app-req))))
    (println "\n=== Static-only routes — aggregate (5 reqs/iter) ===")
    (b "pedestal prefix-tree" #(doseq [r ped-static-reqs] (ped-pt-static r)))
    (b "pedestal map-tree"    #(doseq [r ped-static-reqs] (ped-mt-static r)))
    (b "appia"                #(doseq [r static-reqs] (appia-match appia-static r)))))

;;; ----------------------------------------------------------------
;;; sub-segment params (appia only)
;;; ----------------------------------------------------------------

(defn bench-sub-segment []
  (require '[s-exp.appia :as appia])
  (let [appia-match  (ns-resolve (find-ns 's-exp.appia) 'match)
        appia-router (ns-resolve (find-ns 's-exp.appia) 'router)
        appia-r      (appia-router sub-seg-appia-routes)]
    (println "\n=== Sub-segment params (appia only) ===")
    (doseq [req sub-seg-reqs]
      (b (str "appia " (:uri req)) #(appia-match appia-r req)))))

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
