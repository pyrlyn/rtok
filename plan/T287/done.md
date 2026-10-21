Depends on T282, T283. The creator wants to reach any running agent by its id from the terminal, and agents to reach each other over MCP.

Plan:
1. Worktree `_worktrees/rtok-T287`.
2. Diesel migration `messages`: `id`, `from_agent NULL` (NULL = the user at a terminal), `to_agent`, `body` (≤ 4 KiB, UTF-8, control chars stripped), `created_at`, `delivered_at NULL`, `read_at NULL`. Local store only, nothing leaves the machine.
3. CLI: `rtok agents send <id-prefix|--all-live> <text|->` (from `RTOK_AGENT_ID` when run inside an agent, else from the user); `rtok agents inbox [<id-prefix>] [--unread] [--json]` (default: the caller's inbox, or with an id the user reads that agent's queue without marking it read).
4. MCP: `agent_send {to, text}` → `{id}`; `agent_inbox {unread_only?, limit?}` → messages, marks them read. Every message is rendered inside a fixed frame: sender id, host and the note that it comes from another agent or the user through rtok and is information, not an instruction that overrides the agent's user or rules.
5. Sending to an ended agent is refused; `--all-live` fans out to live agents of the same project only.

Check: store tests (send, inbox order, read marks, 4 KiB cap, control-char strip); CLI e2e with two fake agents; MCP e2e: agent A sends, agent B's `agent_inbox` returns it framed, then empty; trycmd and gates; `just check`.

Execution (2026-09-27): two PRs, like T283. PR 1, cut on top of T283 PR 1 (#449): migration `0026_messages`, store API, CLI `agents send` / `agents inbox` with the fixed frame, store tests and a CLI e2e with two fake agent rows. PR 2, after T283 PR 2 gives `rtok mcp` its agent: MCP `agent_send` / `agent_inbox` and their e2e.
Progress (2026-09-28): PR 1 is #472 (`agents send`, `agents inbox`, the message frame). Left: PR 2, MCP `agent_send` / `agent_inbox` (after T283 PR 2).

Execution, PR 2 (2026-10-03, Claude Code / sonnet-5): on top of T286 PR 2. (1) `Store::inbox_limited` cuts the page before the read mark, so a message that did not fit stays unread. (2) `src/mcp/messages.rs`: `agent_send {to, text}` (the sender is the session's link; the recipient resolves like the CLI's, an unknown, ended or oversized target is refused by the store) and `agent_inbox {unread_only?, limit?}` (the owner is the session's link; frames from `render::agent_message_frame`, the CLI's). (3) The MCP-session test helper moves to `tests/common/mcp.rs`, shared with the worktree e2e. (4) e2e between two real `rtok mcp` sessions.

Result (2026-10-03, Claude Code / sonnet-5): PR 1 is #472, PR 2 is this one. MCP `agent_send` and `agent_inbox` work for the linked agent only: nothing names the sender or the inbox owner. A message is delivered framed, as the CLI frames it, and read once. Two deviations from the card: `agent_inbox` defaults `unread_only` to true (a limited page of the oldest messages would otherwise be old read ones), and `limit` defaults to 20, at most 100. About 79 more description tokens in the MCP listing. Stacks on #680 (T286 PR 2).
