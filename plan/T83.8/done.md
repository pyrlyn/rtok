The `run` → `expand` round trip likely depends on a Unix shell command or a path/newline assumption in the fixture. Read `tests/commands_e2e.rs` and decide whether the command under test needs a Windows-portable replacement or `rtok`'s `run`/`expand` path has a real Windows bug. One family split out of the original T83; see T83.2 for the closing criterion.

Do (Claude Code / claude-opus-5-5, 2026-09-25): a real `rtok run` bug, not the fixture. The windows job of ci run 35244082778 shows awk running but receiving `\BEGIN{…print ""line ""i}"`: `script_for(Cmd)` already quotes a cmd.exe body for cmd.exe (`"…"` with `""`), then `Command::args` quoted it again with MSVC rules (`\"`), which cmd.exe does not parse — any cmd.exe body containing a `"` broke the same way. New `shell_command` in `src/plugins/cmd/run.rs` hands the cmd.exe body to `CommandExt::raw_arg` on Windows (`/D /C` stay ordinary args; other shells unchanged). New `cfg(windows)` unit test `cmd_exe_gets_the_body_verbatim`; the e2e test left the `cfg(windows)` filter in `.config/nextest.toml`. The T83.2 family is a different cause (POSIX syntax — `;`, a newline inside an argument — under cmd.exe) and stays open.

Check: the test passes in the `windows` CI job; `just check` stays green.

Check result: `just check` green on macOS (1771 passed); PR merged only with a green `windows` job.

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
