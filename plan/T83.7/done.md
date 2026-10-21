The `trycmd`-driven CLI snapshot test likely diffs on path separators, line endings, or a Unix-only fixture. Decide whether `rtok`'s own output needs a Windows-safe rendering or the `.toml`/`.stdout` fixtures need a Windows variant. One family split out of the original T83; see T83.2 for the closing criterion.

Do (Claude Code / claude-opus-5-5, 2026-09-25): `src/cli.rs` sets `bin_name = "rtok"`, so usage lines say `rtok`, not `rtok.exe`, on every OS (clap took argv[0]'s file name). `doctor::audit_from` names a skill by `Path::file_name` instead of splitting on `/`; on Windows the report listed `skills\dry-refactoring`. Snapshots: trycmd always turns `.exe` into `[EXE]`, even inside OpenCode's `tool.execute` hook names, so those lines in `help-subcommands`, `completions-{fish,powershell,zsh}` and `report-md` say `tool[..]cute`. `demon-json` log paths use `[..]demon[..]<name>.log`, and `report-md` prints `rtok[EXE]`. `run.toml` and `expand.trycmd` call `/bin/echo`, `SHELL=/bin/sh` and `cat`, so `tests/cli_trycmd.rs` skips them on Windows. The `cli_trycmd` line left the `cfg(windows)` filter in `.config/nextest.toml`.

Check: the test passes in the `windows` CI job; `just check` stays green.

Check result: `cargo nextest run --test cli_trycmd` 3/3 on macOS; PR #371's `windows` job green with the case enabled; `just check` passed every test except `pi_plugin::setup_pi_yes_links_remove_unlinks` (vitest timeout at host load ~35; 5/5 when rerun alone).

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
