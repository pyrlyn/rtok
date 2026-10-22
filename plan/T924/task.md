---
status: in progress
priority: P1
complexity: 3
readiness: 10%
agent: Claude Code / claude-sonnet-5-5
---

# T924. MCP `worktree_*` tools: worktree in the wrong repo, agent ambiguity the agent cannot resolve

`worktree_add` takes only `task` and `slug` (`src/mcp/worktrees.rs`) and uses the server's
repo. Through the shared Claude.app MCP it made an rtok worktree for a cox session
(2026-10-16) and `_worktrees/scull-t3` for a kerf session (2026-10-18). `ambiguous: agents X, Y
share this cwd; use RTOK_AGENT_ID` came 14 times (2026-10-11..13, weft, mailune), but an MCP
caller cannot set the server's environment. `not linked to an agent session` is still logged
after T473: 53 times in `logs` (17 after 2026-10-09), 42 tool errors in transcripts. Related:
T281 (probe, in progress).

Done when: `worktree_*` and `agent_*` tools take an explicit `repo` (or path) and `agent`
argument, `worktree_add` refuses when the repo is not the caller's instead of guessing, and
the ambiguity message names the argument to pass.

Check: MCP tests for the wrong-repo refusal and for an ambiguous cwd resolved by `agent`;
`just check`.

Plan: `with_agent`/`with_repo` schema helpers in `src/mcp.rs`; `Server::agent_for` takes an
optional `agent` (id or prefix) for every tool that acts as the session's agent, never over a
stable (env/own) link, only a live same-host main agent; the ambiguity message names `agent`.
`worktree_add` checks that the target repository is the repository of the agent's recorded
cwd and refuses otherwise; `worktree_add`/`list`/`remove` take `repo`. Files: `src/mcp.rs`,
`src/mcp/{worktrees,messages,agents}.rs`, `src/agents/link.rs`. Verify: MCP tests for the
wrong-repo refusal and for an ambiguous cwd resolved by `agent`; `just check`.
