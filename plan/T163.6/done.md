`archive_ref_ids`' `call_io` query, `archive_in_session`, `archive_decision`, `put_archive_decision`, `session_live_archives`, `live_zone_pointer`, `mark_expanded`, `archive_decision_counts`, `put_read_cache`, `clear_read_cache`, and the tests reading `call_io`/`archive` (`inline_sha256_matches_stored_text`, `spill_archive_carries_session`, `write_api_round_trip_and_spill`'s `call_io` read).

Execution plan: (1) add any missing `table!` columns; (2) rewrite site by site, signatures and row order unchanged; (3) store tests unchanged and green, `just check`. Joins via `inner_join`/`left_join` over `schema.rs`, `NOT EXISTS` via `exists().not()`.

Check: none of the listed functions or tests hold `sql_query`; archive and expand tests green; `just check`.

Do (Claude Code / claude-opus-5-5 supervising claude-sonnet-5, 2026-09-23): all ten functions and three tests moved to the typed DSL; `schema.rs` gains `archive_decisions` (composite key `(session, tool_use_id)` since 0014) with `joinable!` to `archive`. `archive_in_session` keeps its correlated `COUNT(*)` as one query via `.single_value()`. `clear_read_cache` keeps the byte-safe prefix compare (paths may hold `%`/`_`, so no `LIKE`) with a typed `substr` function; the length bound is the key's char count, as SQLite's `length()` on TEXT counts characters. `session_live_archives` maps empty or `NULL` tool to `-` in Rust instead of `COALESCE(NULLIF(..))`. `mark_expanded` and `put_read_cache` bind `crate::log::now()` instead of `unixepoch()`; `purge_calls_older_than` and one test now reuse the same helper instead of their inline `SystemTime` copies.

Check result: `cargo test --lib store::` 43 passed; clippy `-D warnings` and `fmt --check` clean. `sql_query|sql::<|batch_execute` in `mod.rs` 57 → 42. Full `just check` in CI.

Status: done 2026-09-23
Model: Claude Code / claude-opus-5-5
