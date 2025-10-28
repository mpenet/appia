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
             [:get "/assets/*" :assets]})

(let [matcher (a/matcher routes)]
  (a/match matcher {:request-method :get "/posts"}) => [:list-posts nil]
  (a/match matcher {:request-method :get "/post/2025/10/28/stuff"}) => [:get-post {:year "2025" :month "10" :day "28"}]
  (a/match matcher {:request-method :get "/assets/something/meme.gif"}) => [:assets {:* "something/meme.gif"}])
```

## Licenses

* Copyright © 2025 Max Penet - Distributed under the Eclipse Public License version 1.0.
* Copyright © 2023 Nikita Prokopov - Licensed under MIT License.


