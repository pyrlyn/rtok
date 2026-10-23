---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T972. MCP `read` and `outline` reach host skills, scratchpads and parent instruction files

About 40 `mcp/read: path outside cwd` errors on 2026-10-22: `~/.agents/skills/*/SKILL.md`,
`/private/tmp/claude-*` scratchpads, and the parent `AGENTS.md` / `CLAUDE.md` above the repo.
About 10 more reads used the old `_worktrees/` path. `plugins.read.allow_paths` exists, but its
defaults do not cover these. T930 (not in the installed alpha.2) may cover the scratchpads.

Done when: on main, first list which of these paths still fail. Then add the rest to the default
roots, read-only: host skill dirs, the host scratchpad pattern, and `AGENTS.md` / `CLAUDE.md` in
the repo's ancestors. A missing path under a moved worktree root names the current path. The
error for other outside paths names `allow_paths`.

Check: `read` / `outline` tests over a `Vfs` for each root and for the moved-path hint; `just check`.
