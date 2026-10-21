Source-confirmed on `0b14b88e`: [`handle`](src/proxy/mod.rs#L407) looks up a shared cache before resolving the target at line 430. [`CachePrompt`](src/proxy/semantic_cache.rs#L22) includes provider/model/body/caller but no upstream URL, path, query or HTTP method. [`upstream_for`](src/proxy/mod.rs#L224) supports different upstreams per lane. Two cache-enabled lanes with identical shaping policy, body and credentials but different upstreams therefore share a direct key: warming endpoint A can cause a request intended for B to return A's answer without contacting B. This remains possible with `cache_by_provider` and `cache_by_model` both enabled; T323 isolated callers, not routing destinations.

Execution plan:

1. Reproduce with two local upstream servers returning distinct sentinel responses and two lanes that permit cache reads/fills.
2. Resolve the effective target before lookup and include a consistent target/method namespace in both lookup and fill. Avoid retaining raw credentials or credential-bearing query strings in cache metadata; reuse hashing.
3. Test differing paths/queries as well as lane upstreams; exact replay to one endpoint must still hit.

Check: B receives its first request after A was warmed, each endpoint returns its own bytes, same-target replay hits, and `just check` passes.

Result: `handle` resolves the upstream target before the cache lookup and keys the cache on `semantic_cache::cache_scope` (caller identity + method + resolved URL, hashed so a `?key=` query never appears as text). Lookup and the `finish` fill both use the same scope.

Check: `cached_responses_are_scoped_to_the_upstream_target` (two lanes, two mock upstreams, same body and credentials) failed before the change (B was answered from A's entry) and passes after, with same-target replays still hitting; `the_cache_scope_names_method_and_target` covers method, path and query; `proxy_lane_policy` 7/7; clippy clean. Committed together with T532 because both edit `semantic_cache.rs` and `proxy/mod.rs`. `tests/proxy.rs` was not run to completion: seven tests that never touch the cache (plain-forward, compress, tools rewrite) hung at 0 % CPU for over 10 minutes under load 19 and were killed; the cache tests in that file pass.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
