---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
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
