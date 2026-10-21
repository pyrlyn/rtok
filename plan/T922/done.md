The PreToolUse(Bash) rewrite always produces `rtok run -- '<cmd>'` (`src/plugins/cmd/hook.rs`).
When the shell that runs the command has no `rtok` on PATH, every call fails with
`zsh: command not found: rtok`: 8 calls in a row on 2026-10-09 (workspace session, the agent
stopped work) and once on 2026-10-18 (cox). T174 made the hook command itself fail open, not
the command it writes.

Done when: the rewritten command runs the original command when `rtok` cannot be found (an
absolute path to the running binary, or a `command -v` guard), and output is unchanged
otherwise.

Check: a cmd hook test that runs the rewritten line with a PATH lacking `rtok` and gets the
original command's output and exit code; `just check`.

Result: on POSIX the rewrite is now `[cd hops]command -v rtok >/dev/null 2>&1 || rtok() ( for a; do :; done; eval "$a" ); rtok run [--agent id] -- '<cmd>'` (`names::run_guard`, written by `cmd/hook.rs`). When `rtok` resolves in the host's Bash the line runs the real binary with the same argv; when it does not, a stand-in `rtok` evals the last argument — the quoted original command — in a subshell, so output and exit code are the command's own instead of `command not found: rtok`. Windows keeps the bare PowerShell-quoted form. `names::strip_run_wrap` strips the guard, so guard cache keys and `discover` still see the inner command. Tests: `rewrite_runs_the_original_command_when_rtok_is_not_on_path` (sh, bash, zsh; failed with exit 127 before the fix), `rewrite_calls_rtok_untouched_when_it_is_on_path`, guarded keys in the guard key test; three hook tests and three e2e tests match with `contains` instead of `starts_with`. The TypeScript plugins (`plugins/opencode`, `plugins/pi`) write their own `rtok run -- …` and are not covered; follow-up.

Status: done 2026-10-20
Model: Claude Code / sonnet-5.5
