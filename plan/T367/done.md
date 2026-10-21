Found 2026-10-01 (QA audit, #601; still present on `main` @ `aecab806`). A typo prints `indexed 0 files · 0 rows …` with exit 0, so a script or agent thinks the index was built. `src/cli.rs` (the `graph index` arm, `index::run_with` at `:1684`) passes `path` as-is and the walker yields nothing for a missing root. The sibling graph subcommands in `src/cli.rs` and `src/plugins/graph/status.rs` resolve `path` the same unchecked way (suspected, not reproduced).

Repro: `rtok graph index /nonexistent; echo $?` prints the zero counts and `0`.

Done when: every graph subcommand that takes a path checks it is an existing directory (or canonicalizes with the path as context) before walking, and exits non-zero with `Error: /nonexistent: No such file or directory`; the T356 home/`/` refusal stays as it is.

Check: tests for `graph index` and each sibling path-taking graph subcommand with a missing path exit non-zero naming the path; indexing a temp project is unchanged; `just check`.

**Result (2026-10-03, Claude Code / sonnet-5):** one `graph::cli_root` helper (`src/plugins/graph/mod.rs`) resolves the path (cwd by default) and fails on a missing or non-directory path with `<path>: No such file or directory (os error 2)` / `<path>: not a directory` before any walk; `graph index`, `dead`, `impact` (`src/cli.rs`) and `status` (`graph::status::run`) all call it. `index::run_with` keeps the T356 refusal of `/` and `$HOME` unchanged. Checked by two e2e tests in `tests/commands_e2e.rs` (a missing path for all four subcommands exits non-zero naming the path with empty stdout; a file path is rejected and a temp project still indexes) and `just check`.
