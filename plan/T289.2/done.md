Done means: `worktree_adopt {path?, task?}` for the session's linked agent, same code path as the CLI (no agent or owner argument).

Check: MCP e2e.

Result (2026-10-03, Claude Code / sonnet-5): MCP `worktree_adopt {path?, task?}` calls `claim::bind` like the CLI. The agent is the session's link and there is no owner argument. `path` is the worktree or a directory inside it, relative to the server's cwd, which is the project root for many hosts, so a model in a host-made worktree passes its path. A detached HEAD needs `task`. The MCP listing grows to 25 tools (~663 description tokens); the creator's decision on the listing is still pending.
