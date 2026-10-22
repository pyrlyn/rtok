---
status: in progress
priority: P1
complexity: 3
readiness: 100%
agent: Claude Code / claude-sonnet-5-5
---

# T923. Relative MCP paths resolve against the server's checkout, not the caller's worktree

A shared `rtok mcp` (Claude.app, or one server for a session and its worktree subagents) runs
in the main checkout. A relative `read`/`search`/`outline` path then resolves there, so an agent
in `.claude/worktrees/<x>` or `_worktrees/<x>` reads the main checkout's copy without its
edits. About 10 subagents in cox (2026-09-26..29) concluded "rtok read is stale" and dropped to
Bash; 27 `No such file or directory` errors in 12 sessions (2026-10-05..17, e.g. weft
`WeftStudio.WinUI/MainWindow.xaml.cs`, which existed in the session cwd); 24 of 90 empty
`search` results were contradicted by a grep. T351, T435 and T263 cover absolute sibling paths
only.

Done when: a relative path resolves against the caller's root when the server can know it
(roots, the linked agent's worktree), and otherwise the answer names the root it resolved
against, so a wrong root is visible. The tool descriptions say to pass absolute paths from a
worktree.

Check: read-plugin tests with a server root and a caller worktree that differ (relative path
reads the worktree copy, or the answer names the root); `just check`.

Plan:
1. `roots.rs`: a per-call caller root (thread-local, set by a guard) and `roots::cwd()`, the one place `read`/`search`/`tree` take their base from instead of `std::env::current_dir()`. `roots::accepts` admits a candidate only if it is under the process cwd, a client root or a worktree of their repositories (a hook-registered agent cwd is untrusted).
2. `mcp.rs`: before `read`/`search`/`tree`/`outline`, the linked agent's registered cwd (short TTL cache, fail open) becomes the caller root when `accepts` says so; `outline` gets its relative path joined onto it.
3. Failures of those tools and an empty `search` name the root a relative path resolved against.
4. Tool descriptions say to pass absolute paths from a worktree (mirrors: `tests/trycmd/mcp.toml`, `plugins/pi/extensions/rtok.ts`).
5. Tests first: read plugin with a server root and a caller worktree that differ; `just check`.
