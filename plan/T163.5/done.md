`measurement_count`, `upsert_session`, `upsert_model`, `archive_ref_ids`' measurement query, `session_row`, `sessions_by_cwd`, `set_call_ts`, `kv_get`/`kv_set`/`kv_delete`, `recent_hook_inputs`, `calls_since`, `last_measurement_ref`, and the test helpers that touch these tables (`upsert_session_keeps_non_null_attribution`, `write_api_round_trip_and_spill` counts, `memory_recall_totals_sums_recalls_in_the_window_only` ts update). Upserts via `on_conflict`, `INSERT OR IGNORE` via `insert_or_ignore_into`.

Execution plan: (1) add any missing `table!` columns; (2) rewrite site by site, signatures and row order unchanged; (3) store tests unchanged and green, `just check`.

Check: none of the listed functions or tests hold `sql_query`; `just check`.

Do (Claude Code / claude-opus-5-5 supervising claude-sonnet-5, 2026-09-23): 20 raw-SQL sites in `src/store/mod.rs` moved to the typed DSL — `measurement_count`, `calls_since` via `count()`; `upsert_session` via `on_conflict(...).do_update()` with `excluded()` and a `#[declare_sql_function]` `coalesce` (Diesel has no built-in; T163.3 moves it into the shared extension module); `upsert_model` via `insert_or_ignore_into`; `archive_ref_ids`' measurement query via `eq_any`; `session_row` via `left_join`; `recent_hook_inputs` via `inner_join`; `set_call_ts`, `kv_get`/`kv_set`/`kv_delete`, `last_measurement_ref`; three test helpers. `schema.rs` gained the `kv` table (migration 0018). `sessions_by_cwd` was already DSL. Unused `QueryableByName` row structs removed. 313 changed lines, net +5: a one-for-one swap of multi-line statements.

Check result: `cargo test --lib store::` 42 passed; `cargo clippy --lib --tests -- -D warnings` clean; `cargo fmt --check` clean; `sql_query|sql::<|batch_execute` in `mod.rs` 90 → 70, none in the listed functions. Full `just check` left to CI (host disk at 2 GiB free).

Status: done 2026-09-23
Model: Claude Code / claude-opus-5-5
