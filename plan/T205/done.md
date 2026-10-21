Found 2026-09-22 in the surfaces pass: `handle` (`src/proxy/mod.rs:203-251`) is async but does all bookkeeping synchronously on tokio workers — serde parse of up to 256 MB bodies, tokenizer estimates, a fresh `Runtime::open` per compress request (:546-602), archive file writes — and `finish` (:723-806) repeats sync inserts inside `tokio::spawn`. Nothing uses `spawn_blocking`; N concurrent requests pin N workers, and one huge body delays `/health`, other in-flight streams and TTFB.

Plan: move request shaping (`record` + `compress` + `prepare`/`context_edits`/`rewrite_tools`) and `finish`'s store writes into `tokio::task::spawn_blocking`; keep the tee loop and channels async.

Check: `health_stays_fast_while_a_large_request_is_recorded` — a ~20 MB compress request against a slow mock upstream while `GET /health` answers < 250 ms; the `proxy_*` suite unchanged; `just test` green.

Do (2026-09-24): `handle` in `src/proxy/mod.rs` moves request shaping (`record`, `compress`, `prepare`/`context_edits`, `rewrite_tools`) into the new sync `shape_request`, run with `tokio::task::spawn_blocking`. On a `JoinError` it forwards the original body; a `Bytes` clone costs nothing. `finish` is now a sync fn, run with `spawn_blocking` after the tee loop. Test `health_stays_fast_while_a_large_request_is_recorded`: a ~48 MB compress request against a 300 ms mock upstream, while every `/health` poll answers in under 250 ms. The test fails with the fix reverted. Proxy suite 86/86, `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
