`rtok agents install` (and `uninstall`) quits a running desktop app before it writes that host's config and should open it again afterwards (T141). The creator saw the app close and stay closed (2026-10-06). Two causes in `src/agents/restart.rs`: rtok waits at most 5 s for the app to exit, then runs `open -a` anyway, and an Electron app that is still shutting down (or shows a quit prompt) only gets activated and then finishes quitting; and `quit`/`open` check only that the command started, not its exit status, so a failed `open -a` printed no warning and logged nothing.

Done: rtok waits up to 30 s for the app to exit; an app still running after that is not reopened and gets a "restart it manually" warning; a non-zero exit from `osascript`, `open`, `taskkill`, `start` or `killall` is an error, so a failed quit or reopen warns and is logged; unit tests cover the stuck quit and the failed reopen.

Check: `cargo nextest run -E 'test(/agents::restart/)'`; `just check`.

Do: `src/agents/restart.rs` — `run_ok` runs `osascript`, `open`, `taskkill`, `cmd /C start` and `killall` to completion and fails on a non-zero exit with its stderr; `wait_until_not_running` returns whether the app exited, with a 30 s `QUIT_TIMEOUT`; an app still running after it is warned about and not reopened; the three restart warnings share one `warn` helper.

Status: done 2026-10-06
Check result: `cargo nextest run --lib -E 'test(/agents::restart/)'` 13 passed (new `app_that_never_quits_is_written_but_not_reopened`, `reopen_failure_warns_instead_of_erroring`, `run_ok_fails_on_a_non_zero_exit`); `just check` green, 672 passed.
Model: Claude Code / opus-5.5
