Split from T130.2 (2026-09-24). `ledger()`'s pointers carried only a path and an optional archive id. Creator decision (2026-09-24): a pointer carries the ranges of the symbols the session itself touched — `Read` `offset`/`limit` and the `Edit` site (`new_string` located in the file) — each widened to the innermost enclosing definition from the graph index; at most 3 per pointer, newest first; they share the existing brief budget and are dropped before any pointer is; no index rows (or no enclosing definition) means no range.

Plan: (1) `Store::symbol_file_defs(root, path)` (Diesel, definitions of one file with `line`/`end_line`) exposed as a `Host` method whose default returns nothing, so `memory = []` stays free of `graph`; (2) `handoff.rs::ledger()` collects each path's touched spans across its rows, resolves the index root/relative path like `graph::index::canon`, widens and dedupes them; (3) `build_brief` renders `path:a-b name`, and falls back to range-free lines when the ranged text exceeds the budget; (4) unit tests: indexed fixture shows the range, unindexed path shows none, over-budget drops ranges first.

Result: brief lines read `/repo/lib.rs:6-8 beta, 1-4 alpha — rtok expand <id>`. The row's `cwd` is the index root; the file is read only when the index has definitions for it and an `Edit` must be located. The archived brief always keeps the ranges; the shown one drops them first when over budget.

Check: `handoff::tests::pointers_carry_enclosing_symbol_ranges_when_indexed` (`graph` feature) indexes a two-function fixture, touches a `Read` window, an `Edit` and an unindexed `notes.txt`, and asserts `lib.rs:6-8 beta, 1-4 alpha`, a bare `notes.txt` line, and a range-free brief two tokens under budget; the other seven `handoff` tests unchanged and green; `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
