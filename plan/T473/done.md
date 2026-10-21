Desktop-app (Code tab) sessions often have no rtok agent identity: `~/.claude/session-env/<session>/` stays empty, so `RTOK_AGENT_ID` is unset in the agent's Bash tool, and MCP `whoami`/`worktree_*` answer "not linked to an agent session". `rtok worktree remove`, `agents whoami/status/inbox` from an agent's shell cannot name the caller. T453 only works around it for finished worktrees.

Finding (2026-10-08, the creator's own store and `~/.claude/session-env`): across 28 recent desktop sessions the env file exists exactly when rtok recorded a `SessionStart` call for that session, and in most of those (16 of 19 checked) the first `SessionStart` came hours after the session's first hook (resume/compact). The plugin's `SessionStart` (and often the first `UserPromptSubmit`) never reaches rtok at desktop startup, while a user-settings `SessionStart` hook of the same session does run. `CLAUDE_ENV_FILE` is fine; the startup event is what is missing. Claude Code documents `CLAUDE_CODE_SESSION_ID` (https://code.claude.com/docs/en/env-vars, checked 2026-10-08) in Bash/PowerShell tool, hook and stdio MCP subprocesses, equal to the hooks' `session_id`; an MCP server keeps the id it was spawned with.

Plan:
1. `src/store/agents.rs`: `Store::main_agent(host_id, host_session)` (the lookup `register_agent` already does for a sub-agent's parent, shared).
2. `src/agents/link.rs`: `SESSION_ENV` gains `claude` → `CLAUDE_CODE_SESSION_ID`, lookup-only (an MCP's spawn id can be stale after `/clear` or `--continue`, so it never registers a row; no row yet → the old rules run, nothing cached). New `shell_agent(store, env)`: `RTOK_AGENT_ID`, else the main agent of the host session named by a `SESSION_ENV` var.
3. `src/cli.rs` (`agents whoami/status`, `caller_agent`) and `src/worktree/claim.rs::caller` read the caller through `shell_agent`.
4. Tests: link rules (claude env links an existing row, does not register, falls through), `shell_agent`, a CLI test (`agents whoami` with only `CLAUDE_CODE_SESSION_ID`); trycmd strips the var.
5. `research.md` §26: the Claude row and the desktop finding.

Check: `just check`; built binary from this session's own shell: `rtok agents whoami` and MCP `whoami` name this session's agent with no `RTOK_AGENT_ID`.

Result: `CLAUDE_CODE_SESSION_ID` names the caller in `rtok agents whoami/status/send/inbox` and `rtok worktree …` when `RTOK_AGENT_ID` is unset, and links `rtok mcp` to the session's existing agent row (lookup only, never registers). `research.md` §26 records the finding and sources; `docs/agents-and-worktrees.md` (+ ru/uk) says where the id comes from.
