**T22.1 `--format md`** · T22.0 · `src/report/mod.rs`, `src/report/markdown.rs`, `src/cli.rs`
Do: the whole section set as Markdown, straight from the D23 model. Tables, no charts. Every
number is followed by its evidence — row count and window — so the document cannot quietly grow a
figure nobody measured. Markdown first because it needs no renderer: it is the format that proves
the *content* is right before any layout work starts.
Check: on a store with known fixtures, every number in the output is traceable to a row the test
also asserts; an empty store produces a report that says so rather than zeros.
Complexity: 3/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent, effort High)
Check result: green. The eight sections render in the fixed order (Window · Savings · Calls ·
Cache · Expand · Config · Doctor · Recommendations, the last a T22.5 placeholder that says "No
recommendations." on an empty rule set); every section carries its evidence — per-ledger row
counts plus coverage, with measurements/usage honestly labelled "whole ledger (no row times)"
because their readers return no row time. `src/report/` imports only `crate::web::model` (D24):
`model::report_ledgers()` opens the store once and builds the section data entirely on existing
readers (`calls_after`, `list_measurements`, `archive_decision_counts`, `cache::report`,
`usage_sessions`), so `src/store/` needed no edits; `config_entries`, `doctor`, `log::stamp`,
`stats::parse_since` reused as-is. `tests/report.rs` (3 tests) asserts the fixture store's numbers
row-for-row; `empty_store_says_so_rather_than_zeros` pins the no-rows prose (no `| cmd |` table
rows anywhere). CLI: `rtok report [--format md] [--out <path>] [--since <window>]`, `--format` a
ValueEnum with `Md` alone, `[report] format/out/since` keys (D12/D14; asking for html/pdf errors
with "not built yet (T22.2/T22.3)"). `cargo test --test report` 3 passed; `just check` exit 0
(188 unit + all integration suites; jscpd unchanged at 36 on its tree).
Deviations: 9 files / +880 — D12 forces the config pair + docs mirror, D24 forces the model
methods, `tests/report.rs` follows the house fixture pattern; stated in the commit body. Savings
are net per plugin (expand rows negative), with an explicit line that a `Measurement` row carries
no turn count, so tokens are floors, not context-token-turns.
