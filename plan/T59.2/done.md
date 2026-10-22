**T59.2 Canonicalize `cwd` once per `search` / `tree` call** · I-40 · `src/plugins/read/search.rs`
Do: hoist the per-row `dunce::canonicalize(cwd)` out of `display_rel`: `search` / `tree` compute the canonical base once per call (`canonical_base`, threaded through the `ReadFs` adapter so tests can count), `display_rel` takes the base. The undocumented raw-cwd middle strip arm is gone; the doc contract (canonical cwd → walk root → raw) is now the code.
Check: `just check` green; existing search/tree/display_rel tests pass with assertions unchanged; a Vfs unit test with a counting `ReadFs` adapter asserts exactly one `canonicalize` per call and zero per displayed row.
Complexity: 1/5 — one file, ~30 LOC incl. test.
Status: done 2026-09-17
Check result: `cargo test --lib read::search` 20 passed incl. new `display_rel_canonicalizes_base_once_per_call_from_vfs`; full `just check` exit 0 (fmt, clippy `-D warnings`, nextest, build-min, jscpd 1.90 % under threshold) in an isolation worktree (HEAD + own file — the main checkout carried T55.8 WIP). Note: a bare `nextest` rerun flaked on `otel::hooks_stay_fast_with_an_unreachable_endpoint` under full parallel load; it passes standalone twice and passed inside the gate run.
Model: ZCode / GLM-5.3-Flash
