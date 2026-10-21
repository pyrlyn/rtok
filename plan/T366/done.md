Found 2026-10-01 (QA audit, #601; still present on `main` @ `aecab806`). A command killed by SIGKILL (OOM killer, timeout) or SIGTERM comes back as `1`, not `128+signal` (137 / 143), so agents and scripts cannot tell "killed" from "failed" and OOM kills in test runs hide. On Unix `ExitStatus::code()` is `None` for a signal death and both sites map `None` to `1`: `src/plugins/cmd/run.rs:298` (`out.code.unwrap_or(1)`) and `src/mcp/wrap.rs:90` (`code().unwrap_or(1)`).

Repro: `rtok run -- sh -c 'kill -TERM $$'; echo $?` and `rtok run -- sh -c 'kill -KILL $$'; echo $?` print `1`; plain `sh -c 'kill -TERM $$'; echo $?` prints `143`.

Done when: one shared helper maps a Unix signal death (`ExitStatusExt::signal()`) to `128 + sig` where `code()` is `None`, used by both sites; Windows behaviour unchanged.

Check: Unix-only tests for `rtok run` and the `rtok mcp --` wrap path get `143` for SIGTERM and `137` for SIGKILL, and a normal non-zero exit keeps its code; `just check`.

Result (2026-10-03, Claude Code / sonnet-5): added `proc::exit_code` (`src/proc.rs`), which maps a Unix signal death (`ExitStatusExt::signal()`) to `128 + signal` and otherwise returns `code()`; `proc::capture` (the `rtok run` path) and `mcp::wrap::run` (the `rtok mcp --` path) both use it, so SIGTERM gives 143 and SIGKILL 137 at both sites, a normal non-zero exit keeps its code, and Windows is unchanged. Checked: new Unix-only tests (`proc::tests::capture_reports_128_plus_the_signal_of_a_killed_child`, `run_reports_128_plus_the_signal_for_a_killed_command` in `tests/commands_e2e.rs`, `a_server_killed_by_a_signal_exits_128_plus_the_signal` in `tests/mcp_wrap.rs`) each signal only their own `sh`; `just check` green (2143 tests run: 2143 passed, 6 skipped).

Status: done 2026-10-03
Model: Claude Code / claude-sonnet-5
