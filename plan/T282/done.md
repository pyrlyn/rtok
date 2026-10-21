Depends on nothing; blocks T283–T290. Today a session is keyed by the host's own `session_id` (`src/store/schema.rs:151`, `sessions.id`), which collides across hosts, is missing on several (`research.md` §26), and has no status. `agent_id` in `HookInput` (`src/hooks/types.rs:18`) means a sub-agent's context inside one host session, a different thing. D34 defines the rtok agent id.

Done means: every host session rtok sees has exactly one row with a UUIDv7 id, the host, the host's session id, parent agent (for sub-agents), cwd, start, last seen, end, and the last activity; ids resolve from any unique prefix of 4+ hex chars.

Plan:
1. Worktree `_worktrees/rtok-T282`, branch `t282-agent-registry`.
2. Diesel migration `agents`: `id TEXT PRIMARY KEY` (UUIDv7, hyphenated), `host_id`, `host_session_id`, `parent_id NULL` (a sub-agent's parent agent), `cwd`, `started_at`, `last_seen`, `ended_at NULL`, `activity TEXT NULL` (≤ 120 chars, e.g. `Bash: cargo nextest run`, `Edit: src/worktree/add.rs`), `status_text NULL` (set by the agent itself in T284); unique index on `(host_id, host_session_id, parent_key)`. Schema regenerated, no raw SQL.
3. `store::agents`: `register(host, host_session, parent, cwd) -> AgentId` (upsert, returns the existing id on repeat), `touch(id, activity)`, `end(id)`, `resolve(prefix) -> Result<AgentId>` with the errors `unknown` and `ambiguous: <ids>`, `live(idle)`: rows with no end and `last_seen` inside `[agents] idle` (default `30m`, new config key through the one config module, T238).
4. `rtok hook`: every event registers or touches the agent (one indexed upsert, prepared statement); SessionEnd ends it; a sub-agent's events (`agent_id` present) register a child row with `parent_id`. Activity is the tool name plus the first 60 chars of its main argument (command, path, pattern), never file contents or prompt text.
5. Crate `uuid` with `v7` (maintained; add rows to `toolchain.md` and the shared `rust.md` if missing).
6. Hook budget: measure the hot path with the existing hook bench before and after; the upsert must keep `rtok hook` inside its 10 ms budget (D32 resident process included). If it does not, write through the resident hook process or batch.

Check: store unit tests (register is idempotent, sub-agent row, resolve prefix / ambiguous / unknown, idle cut-off); hook fixture tests per hooked host with the session-id field names from §26 (fakes only, `RTOK_HOST_SANDBOX`); hook bench row before/after in the PR; `just check`.

Status: done 2026-09-27 (#439; hook bench p95 9.10 ms PreToolUse, 9.51 ms PostToolUse)
Model: Claude Code / claude-opus-5-5
