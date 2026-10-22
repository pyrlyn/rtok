T329.33 added call metrics to the web live panel and to the shared fold in `src/web/calls_store.rs`: latency p50 and p95 (`Totals::latency`), symbols asked, calls across several projects, fallbacks and capped answers. D27 requires the TUI live calls pane (T480) to show the same numbers. Depends on T480 and T329.33. Done means: the pane shows the five figures from `Totals` for the selected window, with the same wording as the web panel.

Plan: add one metrics line under the KPI line in `LiveCalls::metric_lines` (`src/tui/live_calls.rs`) from `Totals::latency`, `symbols`, `crossed`, `fallbacks`, `caps` with the web wording; take one bar less so the feed keeps its rows at 24 rows; test with the fixture events of `calls_store.rs`; add a `surface_parity` test.

Done: `LiveCalls::metric_lines` in `src/tui/live_calls.rs` adds one line under the KPI line, read from `Totals` (`latency()`, `symbols`, `crossed`, `fallbacks`, `caps`; no second fold) in the web panel's words: `latency p50 30 ms (p95 100 ms)` (`-` before any call has ended, seconds from 1000 ms as the page's `millis`), `symbols asked 3 (1 across projects)`, `fallbacks 1 (1 capped)` (amber when there are fallbacks). The line shares the pane's window, so it follows `w` and the freeze. The bars take one row less (`height - 10`) so the feed keeps its rows on the cramped 24-row Graph page. `tests/surface_parity.rs` gained `call_metrics_exist_on_both_surfaces`.

Check result (2026-10-10): `just check` green: nextest `Summary [ 711.086s] 3229 tests run: 3229 passed (7 slow), 8 skipped`; no `web/src` change, so no SPA gates. The pane tests (`tui::` 59 passed) show 30 ms and 100 ms, 3 symbols, 1 across projects, 1 fallback, 1 capped, and the feed still shows its rows at 24 rows.

Status: done 2026-10-10
Model: Claude Code / sonnet-5.5
