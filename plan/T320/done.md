`proxy_passthrough_body_records_usage_rows` failed on Windows CI (run 36793800254,
`count_tokens` 0 ≠ 1 at `tests/proxy.rs:247`). The proxy's `finish` writes `usage`,
then the provider `tokens` row, then fills the semantic cache — after the body was
streamed. The tests wait only for the `usage` row, then read `tokens` (and, in the
cache-hit test, re-post expecting a cache hit) too early.

Plan: tests wait with a bounded deadline (no fixed sleeps) for the last row they
assert (`tokens`), via one shared poll helper in `tests/proxy.rs`; `finish` fills the
in-memory semantic cache before the `usage`/`tokens` rows, so "usage row present"
implies "cache filled". Fail-open unchanged.

Check: `just check` and `cargo nextest run --test proxy` green on all CI runners; no
read-after-`usage` race left in `tests/proxy.rs`.

Result: PR pyrlyn/rtok#600. `tests/proxy.rs` has one bounded-deadline `eventually` poll (10 s); `t51_tokens` waits for the `tokens` row in the three tests that assert it. `finish` fills the semantic cache before writing `usage`/`tokens`. All 34 proxy tests are green locally; CI is green on every runner.

Status: done 2026-10-02
Model: Claude Code / opus-5.5
