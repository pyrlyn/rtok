Part of T331. `rtok doctor --agent <host>` limits the hooks check to one host (the id of `agents::HOSTS`; an unknown id is an error naming the valid ones), and on Windows a hook command resolves by `PATHEXT` and the `cmd`/PowerShell word rules of the host instead of POSIX words. Depends on T331.2.

Check: the host filter on mocks (known, unknown, a host without hooks), `PATHEXT` cases on a case-insensitive mock `Fs`; `just check`.

Execution (2026-10-03): (1) `hooks::host_id` checks the id against `agents::HOSTS`; `hooks::check_for` filters `check` by host, `fix::fix_broken_for` and `fix::run` take it for `--fix`, and `doctor` filters the report's problems; one `doctor_host` helper in `cli.rs` turns a bad id into an error naming the valid ones. (2) Windows rules sit behind `Env::windows()` (a defaulted trait method, `cfg!(windows)`), so mocks run them on any OS: `split_words` (`"` groups, `\` literal except before a `"`), `%VAR%`, `cmd /c` and `powershell -File`, `PATHEXT` and `PATH` lookup, no exec bit. (3) The case-insensitive file system is a mode of the existing test `Mock`, not a second `Fs`. (4) Tests on the mock: the host filter (known, unknown, a host without hooks), words, `PATHEXT` with differing case, `PATH` and builtins, quotes with `(x86)`, `%USERPROFILE%`, `cmd`/`powershell`, relative paths; trycmd cases for `--agent claude --fix` and an unknown host; `just check`.

Result: `rtok doctor --agent <HOST>` limits the hooks check, and with `--fix` the repair, to one host; an unknown id is an error listing the valid ones. On Windows a hook command resolves by the Windows word rules and `PATHEXT` instead of POSIX words, replacing the "Windows paths are not checked yet" bail of T331.1.

Status: done 2026-10-03
Model: Claude Code / claude-sonnet-5-5
