Depends on T285. Removal today exists only in bulk (`rtok worktree gc`) and in the external `wt.sh done` script; an agent that finished its task has no single-worktree command.

Plan:
1. Worktree `_worktrees/rtok-T286`.
2. `rtok worktree remove <path|task-id> [--agent] [--json]` and MCP `worktree_remove {path|task}`: refuses (exit 1, reason on stderr / in the tool result) when the worktree has uncommitted or untracked files, is locked by another owner or another agent, or is the caller's cwd; never `--force`. Otherwise: unlock, `git worktree remove`, delete the local branch only when merged (squash-aware `is_merged`, `src/worktree/git.rs:57`), release the claim, print what happened and the one-line hint to delete the remote branch.
3. An unmerged clean worktree: removed only with `--keep-branch` (the branch survives, nothing is lost); without it, refused with that hint.
4. Extract the single-worktree removal that `gc` already does (`src/worktree/gc.rs:76`, lock restored on failure) into one function that `gc` and `remove` both call; no second copy.
5. `skills/worktrees/SKILL.md` finish step switches from `gc` to `remove` for "my task is merged".

Check: e2e in a scratch repo: merged clean → gone with branch; unmerged clean → refused, then removed with `--keep-branch`; dirty → refused; another agent's → refused; cwd → refused; gc tests unchanged and green after the extraction; MCP e2e; trycmd and gates; `just check`.

Execution (2026-09-27): two PRs. PR 1, cut on top of T285 PR 1: extract the single-worktree removal from `gc` into one shared function; `rtok worktree remove <path|task-id> [--agent] [--keep-branch] [--json]` with the refusals above; the claim is released; the skill finish step changes; e2e tests in a scratch repo. PR 2, after T283 PR 2: MCP `worktree_remove`.
Progress (2026-09-28): PR 1 on branch `t286-worktree-remove`, stacked on #471; its PR opens when #471 merges. Left: PR 2, MCP `worktree_remove` (after T283 PR 2).

Execution, PR 2 (2026-10-03, Claude Code / sonnet-5): on top of T285 PR 2. (1) `remove::for_agent` is the one remove-and-release path (owner default, `remove::run`, claim release); the CLI `worktree remove` calls it. (2) `src/mcp/worktrees.rs`: tool `worktree_remove {path | task, keep_branch?}`, listed beside `worktree_add`; the agent is the session's link, never an argument, and there is no `owner` argument. A session with no linked agent gets the link error and removes nothing. (3) e2e in `tests/worktree.rs` through `rtok mcp`.

Result (2026-10-03, Claude Code / sonnet-5): PR 1 is #482 (the CLI); PR 2 is this one. MCP `worktree_remove` removes the linked agent's own clean worktree (with its branch once merged), refuses a dirty or unmerged one (`keep_branch` removes an unmerged clean one and keeps the branch), another agent's lock and the cwd, never forces, and releases the claim. The CLI and MCP share `remove::for_agent`; the claim-release warning on the CLI is now unstyled, like `claim::remember`'s. About 57 more description tokens in the MCP listing (18 to 19 tools). Stacks on #676 (T285 PR 2).
