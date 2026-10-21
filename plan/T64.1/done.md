From `research.md` §11 (rtk's four strategies, 2026-09-18). rtk groups similar items — files by directory, errors by type; rtok's rule engine (`src/plugins/cmd/rules.rs`) only keeps, drops, cuts by position and folds adjacent duplicates, and the `ls`/`find`/`tree` formatters just take the first 40 lines.
Done when a rule may set `group = "dir"` (path-per-line output: `find`, `rg -l`, `git status` untracked, `ls -R`) or `group = "diag"` (diagnostics keyed by code or rule id: `cargo` `error[E…]`, `tsc` `TS…`, `eslint` rule, `pytest` exception class), the pass rewrites the lines as `dir/ (N files): a, b, c …` and `E0308 ×N: first message (file:line, …)` before the head/tail cut, stays lossless (raw output archived as today, `expand <id>` trailer), and a fixture per family in `tests/cmd_golden` records before/after bytes that beat the same rule without `group` — a family that does not win is not switched on. T58.5 keeps its per-family formatters; this is the generic pass a TOML rule turns on.

Execution plan:
1. Parse `group = "dir" | "diag"` on a Rule; apply the pass after drop/keep/collapse/dedupe and before the head/tail cut.
2. `dir` rewrites path-per-line output as `dir/ (N files): a, b, c …`; `diag` keys rustc `E…`, `tsc` `TS…`, `eslint` rules, `dotnet` `CS…`, pytest/python exception classes as `E0308 ×N: first message (file:line, …)`.
3. Golden per family vs the same rule without `group`; enable the field in `rules/default.toml` only on a win. Leave T58.5 docker/kubectl/ps formatters; drop the ls/find take(40) stubs so a TOML rule can run. Do not implement T64.2.

Execution plan (T64.1, Cursor / grok 4.6): isolated worktree `t64.1` from `t58.5`. (1) `group = "dir" | "diag"` on `Rule`, applied after drop/keep/collapse/dedupe and before the head/tail cut. (2) Goldens per family vs the same rule without `group`. (3) Enable in `rules/default.toml` only on a win; leave T58.5 docker/kubectl/ps formatters; drop ls/find take(40). (4) Do not implement T64.2.

Shipped: `dir` on `ls` (248→49 B), `find` (539→83 B), `rg -l` (459→83 B); `diag` on `tsc` (1619→107 B), `eslint` (749→48 B), `cargo check` (749→34 B), `dotnet` (849→71 B). Skipped: `grep` (`-n` hits unchanged), `tree` (keep take(40) formatter), `git status` / `pytest` (formatters), `python` (T50.1 traceback does not shrink as `NameError ×1`), `go` (no error codes). Lossless archive + expand trailer unchanged.

Status: done 2026-09-18
Check result: `cargo test --lib -- cmd::` 60 passed in worktree `t64.1`; `group_goldens_beat_the_same_rule_without_group` pins the table.
Model: Cursor / grok 4.6
