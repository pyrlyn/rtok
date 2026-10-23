---
status: todo
priority: P2
complexity: 3
readiness: 0%
agent:
---

# T973. Agent lookup: shared cwd, unlinked sessions, `--agent <provider> / <model>`

2026-10-22 logs: `mcp/whoami: ambiguous: agents 5fa4ff6f, 8b7fdbe4 share this cwd`, 6×
`not linked to an agent session`, and `--agent OpenAI / GPT-6: unknown`. An agent copied the
plan front matter's `agent` value into `--agent` (`src/worktree/claim.rs` `caller`). T281 (the
session ↔ MCP link) and T966 (one lookup, not in the installed alpha.2) cover part of this.

Done when: after T966, list which of these still happen on main. Fix the rest that fall outside
T281: `--agent` given a `<provider> / <model>` string says it wants an rtok agent id and how to
find one. An ambiguous or unlinked `whoami` names the next step. A Codex session resolves like a
Claude Code one, where its hooks give a session id.

Check: tests for each message and for a Codex session lookup; `just check`.
