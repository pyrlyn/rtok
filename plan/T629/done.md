Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` in [with_restart](src/agents/restart.rs#L289). The loop closes each running desktop immediately after its `would_change` check. If a later host's check returns an error, `?` exits before the reopen loop. A probe compiling this exact orchestration with fake processes recorded only `quit:First` when the second host's preflight failed; the write callback was never reached. A write-error control recorded both quits and both reopens. T141/T434 cover ordinary restart and write/quit failures, not a later preflight error after partial shutdown.

Execution plan:

1. Add an orchestration regression with two running desktop hosts and an error in the second preflight. Assert no host remains closed and no write occurs.
2. Complete fallible preflight checks before shutting down any app, or use guaranteed cleanup for every successfully closed app on every exit path. Preserve existing warnings and best-effort reopen semantics.
3. Cover failures on first/middle/last hosts, dry run, no-restart, CLI-only hosts and write errors without launching real applications.
4. Run restart/install orchestration tests and `just check`.

Check: every successfully closed app is reopened even if a subsequent host cannot be planned. Executed evidence used the real orchestration function with injected process/config seams; OS process control was not exercised.

Result: `with_restart` runs every `would_change` preflight before quitting any app, so a later failing preflight closes nothing.

Check: `a_failing_preflight_on_any_host_closes_nothing_and_writes_nothing`, `two_running_desktop_hosts_are_both_closed_then_both_reopened` (fakes only).

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
