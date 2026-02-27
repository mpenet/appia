# appia

Simple router library for clojure. 

It's a fork of [clj-simple-router](https://github.com/tonsky/clj-simple-router)
with some modifications to support named parameters and be more OpenAPI friendly
out of the box.

It's currently used by https://github.com/mpenet/legba/.

## Installation

[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/appia.svg)](https://clojars.org/com.s-exp/appia)

Or via git deps:

```clj 
com.s-exp/appia {:git/url "https://github.com/mpenet/appia.git" :git/sha "..."}
```

## Usage

```clj
(require '[s-exp.appia :as a])

(def routes {[:get "/post/{year}/{month}/{day}/{slug}"] :get-post
             [:get "/posts"] :list-posts
             [:get "/assets/*"]                         :assets
             [:get "/files/{name}.{ext}"]               :file
             [:get "/objects/urn:{type}:{id}"]          :object})

(let [router (a/router routes)]
  ;; exact match
  (a/match router {:request-method :get :uri "/posts"})
  ;; => [:list-posts {}]

  ;; whole-segment params
  (a/match router {:request-method :get :uri "/post/2025/10/28/my-slug"})
  ;; => [:get-post {:year "2025" :month "10" :day "28" :slug "my-slug"}]

  ;; wildcard tail capture
  (a/match router {:request-method :get :uri "/assets/images/logo.png"})
  ;; => [:assets {:* "images/logo.png"}]

  ;; sub-segment params: multiple params within a single segment
  (a/match router {:request-method :get :uri "/files/report.pdf"})
  ;; => [:file {:name "report" :ext "pdf"}]

  ;; sub-segment params: literal prefix + params
  (a/match router {:request-method :get :uri "/objects/urn:book:42"})
  ;; => [:object {:type "book" :id "42"}]
  )
```

Path parameters are enclosed in `{}` and can appear anywhere within a segment —
as a whole segment (`{id}`), as a prefix (`{name}.json`), suffix (`v{major}`),
or multiple within one segment (`{major}.{minor}`). Literal segments always take
priority over parameterised ones.

## Licenses

* Copyright © 2025 Max Penet - Distributed under the Eclipse Public License version 1.0.
* Copyright © 2023 Nikita Prokopov - Licensed under MIT License.


