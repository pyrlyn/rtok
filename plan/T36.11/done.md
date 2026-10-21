**T36.11 retention actually runs** · — · `src/store/mod.rs`, `src/proxy/mod.rs`, `src/mcp.rs`
Do: `purge_calls_older_than` has no caller and `core.retain_calls_days` is read nowhere, so `calls`/`call_io`/`tokens`/`logs`/`usage` and `~/.rtok/archive/` grow without bound on exactly the long-running surfaces `demon` keeps alive.
Check: with `retain_calls_days = 1` and an old row seeded, a proxy/mcp session purges it and its archive file; a test asserts the row count falls.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test --lib retention` — 3 passed (`run_retention_purges_old_call_and_archive`, `retention_runs_on_mcp_session_start`, `retention_runs_on_proxy_session_start`); `mise exec -- cargo test --lib purge_drops` — 1 passed (`purge_drops_old_calls_and_detaches_their_ledger_rows`); T36.2 `live_zone_pointer`, T36.10 `filter_lines`/`max_result_chars`, and T36.20 session/spill/try_from retained.
