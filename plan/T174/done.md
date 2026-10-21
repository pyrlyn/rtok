Found in the 2026-09-22 audit: 380 hook errors `/bin/sh: rtok: command not found` (exit 127) in 7 days, all in projects whose shell `PATH` lacks `~/.ketch/bin` — one non-blocking error on every tool call. D21 says a missing `rtok` fails open and says to install with ketch.

Plan: make the Claude Code plugin's hook command resolve `rtok` (PATH, then `~/.ketch/bin/rtok`) and, when absent, exit 0 silently except one SessionStart note naming `ketch install pyrlyn/rtok`; apply the same to the other host plugins that shell out to `rtok`.

Check: a plugin test runs the hook command with an empty `PATH` and no binary: exit 0, empty stdout except the one SessionStart note; `just test` green.

Result: The exit-127 errors came from the settings-file hooks that `rtok agents install claude` writes (shared by zcode and kimi): they named a bare `rtok hook <event>` with no fallback. On Unix, a bare `rtok` bin is now written as a hook-time resolver that tries PATH, then `~/.ketch/bin/rtok`, then exits 0. It is silent on every event except SessionStart, which gets one `hookSpecificOutput.additionalContext` note naming `ketch install pyrlyn/rtok`. `is_ours` recognises both the old and new shapes, so reinstall and removal stay idempotent. The plugin scripts `plugins/{claude,zcode}/scripts/hook.sh` already resolved rtok; they now print the missing-rtok hint only on SessionStart instead of on every event. Tests: a `/bin/sh -c` run with an empty PATH and a temp HOME, with and without a fake `~/.ketch/bin/rtok`, in both `src/agents/claude` and `tests/claude_plugin.rs`. `just check` green (1647 tests); `just dup` 1.96 %. Codex, Copilot, Cursor and Grok plugin `hooks.json` still call bare `rtok`; that is flagged as a separate follow-up.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
