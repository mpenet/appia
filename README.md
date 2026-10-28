# appia

A fast Clojure router for Ring-style HTTP requests.

- Routes are plain data: a map of `[method path]` to any value.
- Paths use the openapi format, with params anywhere within a segment
  (`/files/{name}.{ext}`, `/objects/urn:{type}:{id}`).
- Matching is strict, deterministic, and fast: static routes in ~13 ns, routes
  with params in ~50–80 ns, faster than reitit-core on the same routes (see
  [Performance](#performance)).

It's currently used by https://github.com/mpenet/legba/.

## Installation

Requires Java 11 or later.

[![Clojars Project](https://img.shields.io/clojars/v/com.s-exp/appia.svg)](https://clojars.org/com.s-exp/appia)

```clj
com.s-exp/appia {:mvn/version "..."}
```

Or via git deps:

```clj
com.s-exp/appia {:git/url "https://github.com/mpenet/appia.git" :git/sha "..."}
```

The router is written in Java, so a git dependency has to be prepared once
before use:

```bash
clj -X:deps prep
```

## Usage

`router` builds a router from a map of `[method path]` to route value. The
value can be anything but `nil`: a handler fn, a keyword, a map of route data.

`match` takes a router and a Ring request, and returns a `[value path-params]`
tuple for the route matching its `:request-method` and `:uri`, or `nil`.

```clj
(require '[s-exp.appia :as a])

(def router
  (a/router {[:get "/posts"] :list-posts
             [:post "/posts"] :create-post
             [:get "/post/{year}/{month}/{day}/{slug}"] :get-post
             [:get "/assets/*"] :assets
             [:get "/files/{name}.{ext}"] :file
             [:get "/objects/urn:{type}:{id}"] :object}))

;; static route
(a/match router {:request-method :get :uri "/posts"})
;; => [:list-posts {}]

;; same path, another method
(a/match router {:request-method :post :uri "/posts"})
;; => [:create-post {}]

;; whole-segment params
(a/match router {:request-method :get :uri "/post/2025/10/28/my-slug"})
;; => [:get-post {:year "2025" :month "10" :day "28" :slug "my-slug"}]

;; wildcard: the rest of the path
(a/match router {:request-method :get :uri "/assets/images/logo.png"})
;; => [:assets {:* "images/logo.png"}]

;; several params within one segment
(a/match router {:request-method :get :uri "/files/report.pdf"})
;; => [:file {:name "report" :ext "pdf"}]

;; literal text around params
(a/match router {:request-method :get :uri "/objects/urn:book:42"})
;; => [:object {:type "book" :id "42"}]

;; no route
(a/match router {:request-method :delete :uri "/posts"})
;; => nil
```

### With Ring

With handler fns as route values, dispatching is a destructuring away:

```clj
(defn get-post [{:keys [path-params]}]
  {:status 200 :body (str "post " (:slug path-params))})

(def router
  (a/router {[:get "/post/{slug}"] get-post}))

(defn app [request]
  (if-let [[handler path-params] (a/match router request)]
    (handler (assoc request :path-params path-params))
    {:status 404}))

(app {:request-method :get :uri "/post/hello"})
;; => {:status 200 :body "post hello"}
```

## Route syntax

| Pattern | Example | Matches |
|---------|---------|---------|
| Literal | `/posts` | exactly `/posts` |
| Whole-segment param | `/{id}` | any non-empty segment, binds `:id` |
| Params within a segment | `/{name}.{ext}` | a segment such as `report.pdf`, binds `:name` and `:ext` |
| Literal text around a param | `/v{major}`, `/{name}.json` | a segment such as `v2`, or `data.json` |
| Wildcard | `/*` | the rest of the path, binds `:*` |

Params are written in `{}` and bind keywords: `{id}` binds `:id`. Their values
are strings, and never empty.

Within a segment, a param ends at the next occurrence of the literal after it,
except that a literal ending the pattern always matches at the end of the
segment:

```clj
;; "/files/{name}.{ext}" on "/files/archive.tar.gz"
;; => {:name "archive" :ext "tar.gz"}
;; "/files/{name}.json" on "/files/data.json.json"
;; => {:name "data.json"}
```

Methods are compared with `=`, so they can be any value, though Ring uses
keywords such as `:get`.

`router` throws an `ex-info` for:

- a route key that is not a `[method path]` vector
- a path that does not start with `/`
- two params with no literal between them, such as `{a}{b}`, which would be
  ambiguous

### Match priority

Overlapping routes are not an error: they are resolved segment by segment, most
specific first.

1. Literal segments (`/login`)
2. Params within a segment (`/{name}.{ext}`): the pattern with the most literal
   text first (`{name}.json` before `{name}.{ext}`), ties in route order
3. Whole-segment params (`/{id}`)
4. Wildcards (`/*`)

When the rest of the URI does not match below a choice, matching backs off to
the next one. With `/{id}` and `/login`, `/login` always matches the literal
route, and `/{id}` everything else.

### Wildcards

A `*` segment matches the rest of the path, zero or more segments; segments
after it in the route are ignored. The `:*` param holds the remainder, and is
left out when nothing follows:

```clj
(def router
  (a/router {[:get "/assets/*"] :assets}))

(a/match router {:request-method :get :uri "/assets"})
;; => [:assets {}]
(a/match router {:request-method :get :uri "/assets/"})
;; => [:assets {}]
(a/match router {:request-method :get :uri "/assets/images/logo.png"})
;; => [:assets {:* "images/logo.png"}]
```

### Trailing slashes

Paths are matched strictly: `/users` and `/users/` are different routes, as
they are different URIs. A trailing slash is an empty last segment, which only
a route ending in `/` matches.

```clj
(def router
  (a/router {[:get "/users"] :list-users
             [:get "/users/{id}"] :get-user}))

(a/match router {:request-method :get :uri "/users/"})
;; => nil
(a/match router {:request-method :get :uri "/users/42/"})
;; => nil
```

To treat both forms the same, normalize the URI before matching, or redirect
to the canonical form in a middleware.

URIs must start with `/`, as Ring requires; others match nothing.

### Path params

The path params map is an immutable map that takes each value out of the URI
when it is read, so params a handler never looks up cost nothing. It behaves
as any Clojure map (`get`, destructuring, `assoc`, `=`, printing, ...), though
its class is `s_exp.appia.PathParams` rather than `PersistentArrayMap`.

## Performance

Benchmarked on JDK 25, Clojure 1.12, Apple Silicon (M-series). Numbers are
nanoseconds per request: the median of many runs calling the matcher
round-robin over the request set, after a warm-up, each run on fresh copies of
the requests so URI hashes are not cached.

### Mixed routes (static + named params)

Routes: `/`, `/login`, `/map`, `/article/{id}`, `/article/{id}/update`,
`/article/{id}/update/{thing}`, `/files/{name}`, matched round-robin.

| Library | ns/req | ns/req, reading params |
|---------|--------|------------------------|
| appia | ~41 | ~79 |
| reitit-core 0.10.1 | ~57 | ~102 |
| pedestal 0.8.2-beta-1 map-tree | ~378 | |
| pedestal 0.8.2-beta-1 prefix-tree | ~381 | |

Appia matches the request map, method included; reitit matches the path
alone. Appia builds param values only when they are read, so the second column,
where each match reads its params as a handler would, is the realistic one.
Versus pedestal, appia is 8–10x faster on parameterised routes.

That said reitit is a great library with a lot of features appia doesn't
support (coercion, malli support & whatnot), if you need these use it.

### Params within a segment

Routes only appia supports:

| Route pattern | Example URI | appia (ns) |
|---------------|-------------|------------|
| `/files/{name}.{ext}` | `/files/report.pdf` | ~47 |
| `/prefix/{x}-{y}` | `/prefix/foo-bar` | ~47 |
| `/obj/urn:{type}:{id}` | `/obj/urn:book:42` | ~60 |

### Reproducing

Benchmark code lives in `dev/s_exp/appia_bench.clj`. The `:bench` alias adds
`dev/` to the classpath; `:bench/reitit` and `:bench/pedestal` pull in the
respective libraries:

```bash
# vs reitit-core 0.10.1
clj -M:bench:bench/reitit -e "(require 's-exp.appia-bench)(s-exp.appia-bench/run-reitit)"

# vs pedestal.route 0.8.2-beta-1
clj -M:bench:bench/pedestal -e "(require 's-exp.appia-bench)(s-exp.appia-bench/run-pedestal)"

# both
clj -M:bench:bench/reitit:bench/pedestal -e "(require 's-exp.appia-bench)(s-exp.appia-bench/run-all)"
```

## Implementation

The router is implemented in Java (`src-java/s_exp/appia`), with
`s-exp.appia` as a thin Clojure API over it. It is built once as a per-method
prefix trie, which matching walks over the URI string in place: literal
segments are compared without allocating, and params are recorded as positions
in the URI, only turned into strings when read.

Fully static routes are also indexed by exact URI, so they match with a single
`HashMap.get`. When all routes for a given method are static, the router uses
only that `HashMap` and builds no trie.

From Java, or to match without a request map, `s_exp.appia.Router` also has
`match(method, uri)`:

```clj
(.match ^s_exp.appia.Router router :get "/posts")
;; => [:list-posts {}]
```

## Development

The Java sources must be compiled before running the tests or a REPL, and
again after changing them:

```bash
clj -T:build compile-java
clj -X:test
```

## License

Copyright © 2025 Max Penet - Distributed under the Eclipse Public License version 1.0.
