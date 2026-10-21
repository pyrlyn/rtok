Found in the 2026-09-22 audit: 77% of Bash result bytes (16.4 MB in 7 days) carry no rtok marker. Much of it is below the size gate by design, but the top groups are large source dumps with no rule: `sed` 2.7 MB, `grep` 2.0 MB, `cat` 1.4 MB, newline-separated multi-command scripts 2.4 MB (only `&&`/`;` chains are split), `git diff` 0.3 MB.

Plan: first split the numbers by "below size gate" vs "no rule matched" in `rtok stats`; then add rules for unbounded multi-file `cat`, large `grep -r` hit lists and newline-joined scripts in `src/plugins/cmd/rules.rs`, coordinated with T176 so bounded reads stay whole.

Check: `rtok stats` shows the unmatched-rule share; rule tests for each new family; saving recorded as `Measurement` rows; `just test` green.

Execution plan (Claude Code / claude-sonnet-5, 2026-09-24):
1. `emit_filtered`/`compress` already write one `raw` `Measurement` for three different reasons (tiny body below the trailer gate; T176 bounded passthrough; a picked rule/formatter that shrank nothing) — all indistinguishable today. Split the third case into a new `kind = "unmatched"` (formatters.rs's two zero-shrink `"raw"` returns only; the tiny-body and bounded-passthrough `"raw"` sites stay `"raw"`, i.e. "below size gate/by design"). `measure::stats::attach_bash_cmd` sums `raw` vs `unmatched` bytes/calls from `store.list_measurements("cmd")` into a new `Report.bash_unmarked: BashUnmarkedRow`, printed as one `to_table` line and JSON field (both `is_empty`-gated so the empty-store goldens hold).
2. Audit `bounded.rs`'s bounded-detection for over-claiming: (a) `cat -n` only requires *a* named file, so `cat -n a.rs b.rs c.rs` (an unbounded multi-file dump) currently passes through whole — tighten to exactly one named file; (b) `grep`/`rg` treat any `-A/-B/-C/-m` as bounded even with `-r`/`-R`/`--recursive`, so a recursive grep with context but no hit cap can return unbounded matches — require an explicit `-m`/`--max-count` when recursive. Both fixes route the now-correctly-unbounded case through the existing `[cat]`/`[grep]`/`[rg]` `rules/default.toml` entries instead of a brand-new rule (no duplicated logic); update/extend `bounded.rs`'s `bounded_forms`/`unbounded_forms` cases.
3. Newline-joined multi-command scripts: `formatters::compress` currently keys the rule purely off `argv[0]`, so `"echo hi\ncat big.rs\ngrep x big.rs"` (one PreToolUse-wrapped string) is filtered as `echo` (no rule, usually unmatched). Reuse `bounded::lex` (the one place this crate parses shell syntax beyond the first word, `cmd/AGENTS.md`) via a small `bounded::pipeline_count` to detect 2+ pipelines in a single-string argv, and route those to a new named `[script]` family/rule in `rules/default.toml` instead of the misattributed first command.
4. Update `src/plugins/cmd/AGENTS.md`'s kind vocabulary line and `docs/comparison.md`'s rule-family count (27 → 28); a short note in `docs/cmd-rules.md` about the `[script]` family.
5. Verify: `mise exec -- cargo nextest run -E 'test(/cmd|rules|stats/)'` then `mise exec -- just check`.

Result: `rtok stats` gains a `bash unmarked` row that splits gate calls (`raw`: below the trailer gate, or a T176 bounded passthrough) from `unmatched` (a rule ran and shrank nothing). `compress` records `unmatched` in the second case. New coverage:
- Recursive `grep` without `-m`/`--max-count` is no longer "bounded". `rg` is excluded because its `-r` means `--replace`.
- `cat -n` counts as bounded only for exactly one file.
- A chain of 2+ distinct programs (`&&`, `;`, newline) goes to the new `[script]` rule. Same-program chains and `cd x && cargo test` keep their family.
- `lex` treats backslash-newline as whitespace.

cmd/rules/stats/run/trycmd tests 300/300, `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
