`src/report/markdown.rs` and `src/report/html.rs` have no unit tests; `tests/report.rs` covers one fixture and the empty store. `insta` snapshots on a fixed model: zero savings, one row, very large numbers, text with `<`, `|`, backticks and newlines. Done when no table breaks, no HTML is injected, and no `NaN` or `inf` is printed.

Check: snapshots committed; `just test` green.

Do (Command Code / claude-fable-5, 2026-09-22): fixed `Document` models in `report::fixtures` — `zero` (no rows), `one_row` (one row per table with `u64::MAX`/`i64::MAX`/`i64::MIN` numbers and `NaN`/`inf` latencies), `hostile` (`<script>alert(1)</script> | \`tick\`` + a newline in every free-form field, plus a corrupt `NaN` expand rate); literals only, no store, no clock, and `doctor::report_fixture` lifted to `#[cfg(test)] pub(crate)` so the Doctor section is shared and deterministic (reuse, no second fixture). Three file snapshots per renderer, six committed under `src/report/snapshots/`. Properties asserted beside the snapshots: markdown keeps every table's width of its header row, escapes `|`, and folds newlines so the hostile cell never splits a row; html escapes `<` (`&lt;script&gt;`, never a raw `<script>`) so nothing is injected; both print no `NaN`/`inf` — which needed one renderer fix: `ms()` now maps a non-finite `f64` to `—` through a new `dec()` helper, and the expand-rate percentage in both renderers routes through it (a `NaN` rate prints `—%`, never `NaN%`).

Check result (2026-09-22): `fmt --check` and `clippy -D warnings` green; `cargo nextest run` — 958 passed / 1 failed / 4 skipped, the one failure being the pre-existing machine-state `agents_real_config windsurf_keeps_the_real_mcp_config_json` (T166, fails on clean `main` too). All six new snapshot tests green and stable across a re-run without `INSTA_UPDATE`; snapshots committed.

Deviations: the markdown header (`store <path>`) and the Doctor code block carry the hostile text verbatim by design — markdown is not HTML and a code block must stay literal; escaping and folding apply to table and list cells, which is where a `|` or newline would break structure.

Status: done 2026-09-22
Model: Command Code / claude-fable-5
