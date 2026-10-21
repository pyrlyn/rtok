Observed 2026-10-07 in Claude Code: sub-agents launched with worktree isolation (cwd under `<repo>/.claude/worktrees/agent-*`) run behind a host guard that checks every Bash command touching git stays inside the worktree. The `cmd` plugin rewrote their commands to `rtok run --agent <id> -- 'git status'`; the guard cannot see inside the wrapper and refused with "this command runs rtok with the text git ... cannot be shown not to be git". In four sub-agents every git command was refused (`git status`, `cd <worktree> && git status`, `/usr/bin/git ...`, `git add`), so none could commit. `never_wrap = ["git"]` is no fix: `skip_wrap` checks only the first token, and leading `cd <dir> &&` hops are stripped before wrapping.

Done means: a Bash command from a sub-agent (the hook payload carries an `agent_id`) is not rewritten when `git` is a word anywhere in it; the main session keeps wrapping git (no guard there); other sub-agent commands stay wrapped. Regression tests name each claim and fail without the fix. `just check` is green.

Rule: word match on the whole command text, not a parse of stages. The guard reads the same raw text, so what it can refuse is exactly what contains the word `git`; a stage or stem match (`stages` + `cmd_stem`) would still wrap `bash -c 'git status'`, `$(git rev-parse HEAD)`, `env X=1 git status`, `xargs git` and `time git status`, which the guard refuses as well. A "word" is a run of alphanumerics and `_` equal to `git` ignoring case, so `/usr/bin/git`, `git.exe`, `Git.EXE`, `.git/`, `git-lfs` match and `github`, `digit`, `my_git` do not. Over-matching only costs compression for a sub-agent, and any doubt leaves the command unwrapped (fail open). Only a present `agent_id` counts, valid-shaped or not: a malformed id already drops `--agent`, but the command still came from a sub-agent.

Plan (as executed):

1. `src/plugins/cmd/hook.rs`: `runs_git(cmd)` (word match above); `pre_tool` returns `None` when `cx.agent_id().is_some()` and `runs_git(full)`.
2. Tests in the same file: sub-agent `git status`, `cd <dir> && git status`, `/usr/bin/git add -A`, `git -C <dir> commit -m x`, `cargo test && git status` stay unwrapped; sub-agent `cargo test` and `cargo test github` stay wrapped; main-session `git status` stays wrapped. Check each fails without the fix by stashing the `pre_tool` line.
3. Hosts: `hooks/mod.rs` builds the `Ctx` for every host from the payload `agent_id`, so the fix reaches any host that sends one; no per-host code.
4. `just check`; commit `fix: T437 ...`; close in `done.md`; push the branch and open a PR (no merge).

Result: `runs_git` and the early return in `pre_tool` (`src/plugins/cmd/hook.rs`); tests `sub_agent_git_commands_are_never_wrapped`, `sub_agent_non_git_commands_stay_wrapped`, `main_session_git_commands_stay_wrapped`. The shared dispatch in `src/hooks/mod.rs` builds the `Ctx` from the payload `agent_id` for every host, so any host that sends one gets the same behaviour. Reaches the plugin hook only with the next installed rtok binary.

Status: done 2026-10-07
