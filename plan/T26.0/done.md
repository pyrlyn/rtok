**T26.0 `just dup`** · - · `.jscpd.json` (new), `justfile`, `mise.toml`
Do: jscpd over `src/` and the SDK crate — token-based (Rabin-Karp) with a Rust tokenizer, config in
`.jscpd.json`, `min-tokens 50`, `threshold 3`, console reporter only so it writes no artefact. It
joins `check`, so a task that copies a block instead of extracting a helper fails before review.
The threshold sits just above what the tree measures today (2.14 % of lines, 46 clones), which
stops new duplication without demanding a refactor first; T26.1 lowers it after the cleanup.
Not chosen: `similarity-rs` is AST-based and Rust-native, which is the better shape for this
question, but it is a `cargo install` this repo would have to pin and build; revisit it if jscpd's
token matching turns out to report noise. PMD's CPD is a JVM to install for the same answer.
Check: `just dup` is green on the tree as it stands and exits non-zero at `--threshold 1`, so the
gate is known to fail rather than merely to run; `just check` includes it.
Status: done 2026-09-09 · Model: Opus 5
Check result: green — `just dup` exits 0 on the tree (46 clones, 2.14 % of lines, 2.60 % of tokens,
72 files) and exits 1 with `--threshold 1`, so the gate fails when it should. Verified both through
`mise exec -- jscpd` (the recipe's default) and through the `JSCPD` override. Baseline for T26.1:
the clones are concentrated in `src/proxy/mod.rs`.
Deviations: one, and it is an addition rather than a shortcut. `.agents/skills/dry-refactoring/`
(plus the `.claude/skills` symlink) is jscpd's own refactoring workflow, installed at the user's
request with `npx skills add`. It is prose — a checklist for turning a clone report into an
extraction — with no code and nothing executable, and it belongs beside the gate that produces the
report T26.1 will work from.
