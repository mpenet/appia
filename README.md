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
All numbers are nanoseconds after JIT warmup.

### Static-only routes

When all routes for a method are static (no path parameters), appia detects this
at build time and uses a single `HashMap` lookup on the full URI — no trie walk,
no segment splitting.

Routes: `/`, `/login`, `/logout`, `/about`, `/health` — 5 requests per iteration.

| Library | ns/iter | ns/req |
|---------|---------|--------|
| appia | ~235 | ~30–35 |
| reitit-core 0.10.1 | ~165 | ~14–40 |

### Mixed routes (static + named params)

Routes: `/`, `/login`, `/map`, `/article/{id}`, `/article/{id}/update`,
`/article/{id}/update/{thing}`, `/files/{name}` — 7 requests per iteration.

| Library | ns/iter | notes |
|---------|---------|-------|
| appia | ~1130 | prefix trie, `HashMap` per-node static lookup |
| reitit-core 0.10.1 | ~756 | compiled Java matchers |

Per request:

| URI | reitit (ns) | appia (ns) |
|-----|-------------|------------|
| `/` | 36 | 38 |
| `/login` | 26 | 87 |
| `/article/123` | 88 | 141 |
| `/article/123/update` | 99 | 186 |
| `/article/123/update/thing` | 128 | 325 |

Reitit is faster on comparable routes because it compiles routes into stateless
Java matcher objects and pre-selects a lookup strategy (flat map vs trie) per
router at build time. Appia trades some of that build-time specialisation for a
simpler implementation and support for sub-segment parameters.

### Sub-segment params

Routes only appia supports (params within a single path segment):

| Route pattern | Example URI | appia (ns) |
|---------------|-------------|------------|
| `/files/{name}.{ext}` | `/files/report.pdf` | ~217 |
| `/prefix/{x}-{y}` | `/prefix/foo-bar` | ~203 |
| `/v{major}.{minor}` | `/v1.23` | ~163 |
| `/obj/urn:{type}:{id}` | `/obj/urn:book:42` | ~215 |

## Implementation

The router is built once as a per-method prefix trie. Each trie node holds:

- a `java.util.HashMap` for static (literal) segment children — O(1) lookup
- a flat `Object[]` of sub-segment pattern entries (alternating `parts`/`child`)
- a single named-param child (`Object[]` of `[name, child-node]`)
- a wildcard handler

Nodes are plain Clojure maps during construction, then converted to `Object[]`
by a `freeze-node` pass at the end of `router`. At match time the trie is walked
with direct array index access (`aget`) and `HashMap.get` — no keyword lookups.
Sub-segment patterns use `String.regionMatches` for in-place literal comparison
(no substring allocation per candidate).

## Licenses

* Copyright © 2025 Max Penet - Distributed under the Eclipse Public License version 1.0.
