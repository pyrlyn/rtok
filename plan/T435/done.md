Found 2026-10-06: after T351, `~/.rtok/errors.log` still logs `path outside cwd` for `_worktrees/rtok-<task>/…` paths from sessions working in `apps/rtok`, and subagents in task worktrees could not read their own files through `rtok read`. In Claude.app's Code tab the session's `rtok mcp` ran with cwd `apps/stator` (`tree` listed stator's files), so the session's own repository reached the server only as a `roots/list` root. T351 looked up worktrees only for the cwd's repository, so `apps/rtok` itself passed (client root) while its worktrees did not.

Done when: a path inside any worktree of the repository of a `roots/list` root is accepted like one of the cwd's repository; unrelated directories and scratchpads stay refused.

**Result (2026-10-06).** `roots::dynamic` in `src/plugins/read/roots.rs` lists the worktrees of the cwd and of every client root (each listing cached 30 s as before, looked up only after the cheap checks fail). `guard_accepts_sibling_worktree_and_client_roots` gains the case cwd = unrelated dir, client root = main checkout → the linked worktree is accepted and a scratchpad is not; it failed before the fix. `src/plugins/read/AGENTS.md` updated.

Status: done 2026-10-06
Model: Claude Code / claude-opus-5-5
