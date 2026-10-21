**T3.6 measurement wiring** · T3.1–T3.4 · `src/plugins/cmd/mod.rs`
Do: every run writes `Measurement { kind: formatter|rule|raw, before, after }`; `rtok stats --plugin cmd` shows per-family savings and archive hit count (how often `expand` was called — the honesty metric).
Check: after 3 runs, `rtok stats --plugin cmd --json` has 3 rows with before ≥ after.
Status: done 2026-09-02 · Check: `three_runs_stats_plugin_cmd_json_has_rows` — 3 `rtok run` → `plugin_json` 3 rows, each before ≥ after. `make check` green. Deviation: 4 files (`cmd/run.rs`, `store/mod.rs`, `measure/stats.rs`, `cli.rs`); kind is `raw`/`rule` until T3.3 formatters.
