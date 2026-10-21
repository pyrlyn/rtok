**T19.2 WebSocket snapshot** · files: `src/dashboard/mod.rs`
Do: `/health`, `/ws` snapshot of plugin pages + usage; stats widget data for `saves_tokens` plugins.
**Check:** `cargo test -q dashboard::` — measure has no stats, cmd does after a Measurement row.
Status: done 2026-09-08
Model: Cursor Grok 4.6
Check result: `cargo test dashboard::` — `snapshot_lists_catalogue_and_hides_stats_on_measure` green; `tests/dashboard.rs` `dashboard_health_and_index` green (`/health` ok, `/` serves the Slint canvas host). `just check` green at T19.1.
