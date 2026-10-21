Found 2026-09-22 in the store/accounting pass: `report_window.measurements` / `report_savings` (`src/web/model.rs:578-631`) sum only the 11 catalogue plugin ids via `list_measurements(id)` while labelling the numbers "Whole-ledger counts" — out-of-tree/WASM plugin measurements never appear; `stats --plugin` excludes `kind == "expand"` rows (:356-392) while `Model::plugin_stats` (:1204-1217) includes them; `otel_saved_totals` (`src/store/otel.rs:173-180`) sums per (plugin, kind) over all rows. The same store yields different "saved" totals on the report vs the OTLP export and different `rows` for one plugin on `stats` vs the Plugins page. Compounding: every `saves_tokens` page loads **all** `Measurement` rows into memory per 2 s tick and `report_window` adds an N+1 per session (`list_measurements` has no LIMIT — `src/store/mod.rs:1212-1220`). Breaks D3/D24 ("`rtok report` renders; it never computes a number of its own").

Plan: one `Store::measurement_totals()` SQL aggregate (GROUP BY plugin, kind; plus grouped `usage` counts to kill the N+1) consumed by `report_window`, `report_savings`, `otel_saved_totals` and both `plugin_stats`, with one consistent expand-row policy.

Execution plan:
- `Store::measurement_totals()` (`src/store/mod.rs`): Diesel `group_by((plugin, kind))` + `count_star()`/`sum()`, plus `count_measurements()`/`count_usage()` (whole-ledger `COUNT(*)`, no catalogue filter).
- Expand-row policy: keep `ReportSavings::saved`'s existing documented contract — an `expand` row counts negative (a cost, not a saving), not dropped. Applied everywhere so totals net out consistently instead of disagreeing.
- `otel_saved_totals` (`src/store/otel.rs`): rebuilt from `measurement_totals()` instead of raw `sql_query` (the crate's no-raw-SQL rule — this GROUP BY was always DSL-expressible).
- `report_window`/`report_savings` (`src/web/model.rs`): read `count_measurements`/`count_usage`/`measurement_totals` instead of per-catalogue-plugin and per-session loops — now covers every plugin the ledger has seen.
- `plugin_stats` (CLI, `src/web/model.rs`): stop excluding `kind == "expand"` from the `rows` listing (still isolates `archive_hits` separately) so its row count matches the dashboard's.
- `Model::plugin_stat_totals` (`src/web/model.rs`, replaces the private `plugin_stats` method): one `measurement_totals()` read for the whole Plugins page instead of one `list_measurements` per catalogue plugin.
- Tests: `plugin_stats_matches_sql_aggregates_on_10k_rows` (`src/web/model.rs` tests) and `report_totals_agree_with_the_store_and_otel_export` (`tests/report.rs`).

Check: fixture seeding a non-catalogue plugin's rows + an expand row — `report_window.measurements == store.count_measurements()`, `report_savings.total_saved == otel_saved_totals().sum(saved)`, `rows` equal in both `plugin_stats`; `plugin_stats_matches_sql_aggregates_on_10k_rows`; `just test` green.

Result: One `Store::measurement_totals()` (Diesel `GROUP BY plugin, kind`, no raw SQL) feeds `report_savings`, `otel_saved_totals` and the Plugins page; `report_window` reads `count_measurements`/`count_usage`, so N+1 loops are gone and out-of-tree/WASM plugins count. One expand policy everywhere: an `expand` row is kept and counts negative (`ReportSavings::saved`'s contract); `stats --plugin` no longer drops expand rows from `rows`. Tests `plugin_stats_matches_sql_aggregates_on_10k_rows` and `report_totals_agree_with_the_store_and_otel_export`; `just check` green (1607 tests).

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
