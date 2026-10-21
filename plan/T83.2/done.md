The `cfg(windows)` `default-filter` in `.config/nextest.toml` (T82) skips four tests on `windows-latest`: `one_arg_compound_command_runs_as_one_script`, `exit_3_is_preserved`, `printf_two_lines_exit_0_no_trailer`, `three_runs_stats_plugin_cmd_json_has_rows`. Find out whether `plugins/cmd/run.rs` hardcodes a POSIX shell (`sh -c`) or exit-code assumption that needs a `cfg(windows)` branch (`cmd /C` or PowerShell), or the tests themselves assume a Unix shell on PATH; fix accordingly and delete the line. One family split out of the original T83 (all families and sources: `done.md` → T83.1, which fixed the log/demon rotation family). Closing criterion for the whole split: once every T83.x below has emptied its line from the `cfg(windows)` override in `.config/nextest.toml`, delete the override and move `windows` out of `continue-on-error` into `revert-on-failure`'s `needs` (or into the `check` matrix if `just check` runs on Windows).

Do (Claude Code / claude-opus-5-5, 2026-09-25): not `run.rs` — `resolve_shell` already falls back to cmd.exe and T83.8 fixed the cmd.exe body quoting. The tests hardcoded `printf` and `sh -c`, which cmd.exe lacks. New test helpers `on_cmd` and `print_argv`: under cmd.exe the same bytes come from `type <file>` (the T83.11 pattern), the compound case chains two `type`s with `&`, and the exit-code case uses `exit 3`; POSIX shells keep their commands. The line left the `cfg(windows)` filter in `.config/nextest.toml`. The closing criterion for the whole split moved to T83.4, the last family.

Check: the four tests pass in the `windows` CI job; `just check` stays green.

Check result: the four tests PASS in PR #372's `windows` job (1723 run, 1723 passed); `cargo nextest run --lib plugins::cmd::run` 20/20 on macOS; `just check` passed every test except `pi_plugin::setup_pi_yes_links_remove_unlinks` (the same vitest timeout under host load as in T83.7).

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
