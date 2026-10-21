---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
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
