`tests/trycmd/` pins help and happy output. Add fixtures for a bad value or missing argument on each subcommand (exit 2, clap message) and for `parse_since` rejects (`--since 5x`, `--since -1d`, empty).

Check: one fixture per subcommand; `just test` green.

Do (Command Code / claude-fable-5, 2026-09-22): one literate `tests/trycmd/bad-args.trycmd` — 29 cases the existing `cli()` glob picks up, one fixture per top-level subcommand plus the `parse_since` trio. Clap-level cases (`? 2`, clap message, config never loaded): missing values (`--call`, `--port`, `--tick-secs`, `--plugin`, `--runs`, `--config`, `--cmd`, `--lines`), missing required arguments (`hook <EVENT>`, `expand <ID>`, `worktree add`, `agents install`, `config get`, `memory import`, `graph impact`, `guard check`) and invalid values (`completions nu`, `report --format json`, `demon start nosuch`). A group command without a verb prints its help rather than an error (clap's `arg_required_else_help`), so each group's fixture drives its required-arg verb for a real clap message. The `parse_since` trio reaches run time (`? 1`, isolated `HOME`/`RTOK_HOME`): `--since 5x` → `Error: bad --since unit in 5x`, `--since=-1d` → `Error: bad --since -1d`, `--since ''` → `Error: bad --since [..]`. Row in `tests/trycmd/README.md`.

Check result (2026-09-22): `--test cli_trycmd` 2/2 green — 46 cases including the 29 new ones, `every_command_has_a_trycmd_case` unaffected; `fmt --check` and `clippy -D warnings` green; full `cargo nextest run` — 958 passed / 1 failed / 4 skipped, the one failure being the pre-existing machine-state `agents_real_config windsurf_keeps_the_real_mcp_config_json` (T166, fails on clean `main` too).

Deviations: the group-verb choice above; `--since -1d` is pinned as `--since=-1d` (a bare `-1d` is consumed by clap as a flag before `parse_since` sees it); the inline elision marker in trycmd is `[..]` — a literal `...` matches literally mid-line.

Status: done 2026-09-22
Model: Command Code / claude-fable-5
