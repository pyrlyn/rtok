Six plugin test files (`tests/{grok,codex,cursor,copilot,cline,claude}_plugin.rs`) each carried their own copy of the same harness: run a hook command through `/bin/sh` with an empty `PATH` and a temp `HOME` (expect exit 0 and silence), then again with a fake `~/.ketch/bin/rtok` (expect it to be exec'd). AGENTS.md: no duplicated logic.

Plan:
1. `tests/common/mod.rs`: `HookShell` (unix) — a temp `HOME` holding an empty `PATH` dir; `run(command)` (`sh -c`) and `sh(args)` return `(exit 0?, stdout)` with `{}` on stdin and stderr dropped; `install_fake_ketch_rtok(script)`; `KETCH_ECHO` (the shared fake); the dir is removed on `Drop`.
2. Switch the grok, codex, cursor, copilot (bash), cline and claude (`hook.sh`) tests to it; no assertion changes. The Windows PowerShell twin and Claude's PATH-exec test use a different harness and stay.
3. `tests/devin_plugin.rs` (T88) is not on `main` yet; it uses `HookShell` when it lands.

Check: the six test files assert what they asserted before and pass; `just check` green; ≤300 LOC.

Result: `common::HookShell` and `common::KETCH_ECHO` replace seven hand-rolled temp-dir/`Command`/fake-ketch blocks (111 lines added, 201 removed). Cursor keeps its heredoc wrapper and builtins-only fake as arguments; Cline keeps its own `run_hook` (it runs the script directly with system `PATH`) and takes only the `HOME` and the fake from the helper; `assert_eq!(code, Some(0))` became `assert!(ok)`, the same check on Unix. The six test binaries pass (34 tests).

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
