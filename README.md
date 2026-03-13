# appia

A fast Clojure router for Ring-style HTTP requests.

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
             [:get "/posts"]                            :list-posts
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
  ;; => [:object {:type "book" :id "42"}])
```

## Route syntax

Routes are a map of `[method path]` → handler. The handler can be any value.

| Pattern | Example | Matches |
|---------|---------|---------|
| Literal segment | `/posts` | exactly `/posts` |
| Whole-segment param | `/{id}` | any single segment, binds `:id` |
| Sub-segment param | `/{name}.{ext}` | one segment with literal delimiter, binds `:name` and `:ext` |
| Literal prefix/suffix | `/v{major}` or `/{name}.json` | segment with fixed text around a param |
| Wildcard | `/*` | matches the rest of the path, binds `:*` to the remainder |

Path parameters are enclosed in `{}` and can appear anywhere within a segment —
as a whole segment (`{id}`), as a prefix (`prefix-{id}`), suffix (`{name}.json`),
or multiple within one segment (`{major}.{minor}`).

### Match priority

When multiple routes could match a request, the most specific wins:

1. Static (literal) segments beat parameterised ones
2. Named whole-segment params (`{id}`) beat wildcards (`*`)
3. Sub-segment patterns (`{name}.{ext}`) are tried after static, before whole-segment params

Overlapping routes are therefore not a configuration error — they are resolved
deterministically by the trie at match time. Routes like `/{id}` and `/login`
can coexist; `/login` will always win for that literal path.

### Wildcard semantics

A trailing `/*` matches zero or more remaining segments. When there are remaining
segments, the `:*` key in the params map contains them joined with `/`:

```clj
(a/match r {:request-method :get :uri "/assets/"})
;; => [:assets {}]          ; no :* key when nothing follows

(a/match r {:request-method :get :uri "/assets/images/logo.png"})
;; => [:assets {:* "images/logo.png"}]
```

## Performance

Benchmarked on JDK 21, Clojure 1.12, Apple Silicon (M-series).
All numbers are nanoseconds (criterium mean). See `dev/s_exp/appia_bench.clj`
and the `:bench` aliases in `deps.edn` to reproduce.

### Static-only routes

When all routes for a method are static (no path parameters), appia detects this
at build time and uses a single `HashMap` lookup on the full URI — no trie walk,
no string scanning.

Routes: `/`, `/login`, `/logout`, `/about`, `/health` — 5 requests per iteration.
reitit-core has no separate static-only fast path so it is omitted here.

| Library | ns/iter | ns/req |
|---------|---------|--------|
| appia | ~233 | ~47 |
| pedestal 0.8.2-beta-1 map-tree | ~375 | ~75 |
| pedestal 0.8.2-beta-1 prefix-tree | ~1789 | ~358 |

### Mixed routes (static + named params)

Routes: `/`, `/login`, `/map`, `/article/{id}`, `/article/{id}/update`,
`/article/{id}/update/{thing}`, `/files/{name}` — 7 requests per iteration.

| Library | ns/iter | notes |
|---------|---------|-------|
| appia | ~781–787 | in-place URI walk, typed trie nodes |
| reitit-core 0.10.1 | ~529 | compiled Java matchers |
| pedestal 0.8.2-beta-1 map-tree | ~7638 | |
| pedestal 0.8.2-beta-1 prefix-tree | ~7739 | |

Per request:

| URI | appia (ns) | reitit (ns) | pedestal pt (ns) |
|-----|------------|-------------|------------------|
| `/` | ~50 | ~13 | ~82 |
| `/login` | ~71 | ~17 | ~332 |
| `/map` | ~66 | ~16 | ~368 |
| `/article/123` | ~114–117 | ~89 | ~1326 |
| `/article/123/update` | ~147–152 | ~104 | ~1410 |
| `/article/123/update/thing` | ~196–199 | ~142 | ~2511 |
| `/files/readme` | ~114–117 | ~89 | ~1241 |

Reitit has an edge across the board because it compiles routes into stateless
Java matcher objects at build time. Appia trades some of that build-time
specialisation for a simpler implementation, a static-only fast path, and
sub-segment parameter support that reitit cannot express. Versus pedestal, appia
is 10–13x faster on parameterised routes.

### Sub-segment params

Routes only appia supports (params within a single path segment):

| Route pattern | Example URI | appia (ns) |
|---------------|-------------|------------|
| `/files/{name}.{ext}` | `/files/report.pdf` | ~193 |
| `/prefix/{x}-{y}` | `/prefix/foo-bar` | ~181 |
| `/obj/urn:{type}:{id}` | `/obj/urn:book:42` | ~203 |

## Implementation

The router is built once as a per-method prefix trie. Each trie node is a
`TrieNode` deftype holding:

- a `java.util.HashMap` for static (literal) segment children — O(1) lookup
- an `Object[]` of `Pattern` deftypes for sub-segment children
- a `Param` deftype for the whole-segment named-param child
- a wildcard handler

Nodes are plain Clojure maps during construction, then converted to typed
deftype instances (`TrieNode`, `Param`, `Pattern`) by a `freeze-node` pass at
the end of `router`. At match time the trie is walked by scanning the raw URI
string in-place (no pre-splitting into segments): each recursive frame advances
a `^long pos` index to the next `/`, extracts the current segment with a single
`String.substring` call, and dispatches via `HashMap.get` for static children
or direct deftype field access for param/wildcard children. Sub-segment patterns
use `String.regionMatches` for literal comparison without allocating substrings.

When all routes for a given method are purely static, `router` detects this at
build time and replaces the entire trie with a single `HashMap` of
full-URI → handler, reducing a match to one `HashMap.get`.

## Benchmarks

Benchmark code lives in `dev/s_exp/appia_bench.clj` and uses
[criterium](https://github.com/hugoduncan/criterium) for statistically stable
JVM measurements. The `:bench` alias adds `dev/` to the classpath and pulls in
criterium; `:bench/reitit` and `:bench/pedestal` pull in the respective
libraries:

```bash
# vs reitit-core 0.10.1
clj -M:bench:bench/reitit -e "(require 's-exp.appia-bench)(s-exp.appia-bench/run-reitit)"

# vs pedestal.route 0.8.2-beta-1
clj -M:bench:bench/pedestal -e "(require 's-exp.appia-bench)(s-exp.appia-bench/run-pedestal)"

# both
clj -M:bench:bench/reitit:bench/pedestal -e "(require 's-exp.appia-bench)(s-exp.appia-bench/run-all)"
```

## Licenses

* Copyright © 2025 Max Penet - Distributed under the Eclipse Public License version 1.0.
