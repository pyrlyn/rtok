An agent must know its id to report it, to claim worktrees and to message others. Epic; shipped in four PRs.

Done: PR 1 (#449, 2026-09-28): the SessionStart/SubagentStart line `rtok agent id: <short> (full: <uuid>). ...` as a priority-10 offering inside the injection budget (none when `[agents] enabled = false`), `RTOK_AGENT_ID` exported through Claude Code's `CLAUDE_ENV_FILE` (cited in the `src/hooks/mod.rs` doc comment and `research.md` §26), and `rtok agents whoami [--json]`; T473 later made the CLI fall back to the host-session rule when `RTOK_AGENT_ID` is unset. PR 2 was split: T283.1 (MCP agent link at `initialize`, `rtok mcp --host`, MCP `whoami`, hook-less hosts register their own row), T283.2 (`--host <id>` in every host's MCP entry), T283.3 (the nearest-common-ancestor pid rule). The hook fixtures (line byte-stable but for the id, none when agents are disabled), the MCP e2e `whoami` tests and `tests/agents_whoami.rs` plus `tests/trycmd/agents-whoami.trycmd` cover the Check.

Result: every step of the card is on main. The one open item, the creator's live probe that only confirms the doc-derived link rule per host, belongs to T281 (still in progress), which writes that table into `research.md` §26.

Status: done 2026-10-11
Model: Claude Code / claude-sonnet-5.5
