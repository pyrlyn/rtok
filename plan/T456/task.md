---
status: in progress
priority: P1
complexity: 4
readiness: 20%
agent: Claude Code / claude-opus-5-5
---

# T456. Claude desktop: keep the shared desktop MCP entry from shadowing the session's own rtok MCP

Moved from `roadmap.md` (approved by the creator 2026-10-08 after the T455 investigation).

In the desktop app's Code tab every `mcp__rtok__*` call is served by the one `rtok mcp` that
Claude.app spawns from `claude_desktop_config.json` (`mcpServers.rtok`): cwd of an unrelated
project, no `CLAUDE_*` env, shared by every session. The session's own server (the
`~/.claude.json` `mcpServers.rtok` entry, a child of `claude`) runs but never serves a call. No
link rule in `src/agents/link.rs` can name a session from that shared process, so MCP
`whoami`, `worktree_*` and `agent_*` answer `Link::Outside` there (T455), and T454's env rule
does not reach it. T924's explicit `agent` argument is a workaround, not a fix.

Done when: in a Code-tab session the call reaches the session's own `rtok mcp`, and MCP
`whoami` links by `ancestor` or `env` without an explicit `agent`. The Claude Desktop chat
still reaches rtok through its own entry. The fix is install-side: the desktop entry is
written so it no longer shadows the session's server (another server name or args), as an
amendment to T275's naming decision, recorded in `src/agents/claude/README.md`. Install,
update and remove keep every other key of both files byte-for-byte; an existing
`mcpServers.rtok` desktop entry is migrated, not duplicated.

Check: a test that install writes the desktop entry under the new name or args and removes the old one, with `~/.claude.json` untouched; a live Code-tab session's MCP `whoami` links by `ancestor` or `env` (noted in `done.md`); `just check`.

Plan:
1. Verify live, and from the vendor docs if they say it, how the Code tab merges
   `claude_desktop_config.json` with the session's MCP servers: by server name, by command, or
   desktop-first. Record the finding with its source in `research.md` §26.
2. Pick the smallest entry change that lets the session's server win (for example a distinct
   name such as `rtok-desktop`, or args that mark it). If the Code tab merges by command, a
   name change will not help; stop and ask the creator.
3. `src/agents/claude/mod.rs` (desktop install/update/remove), `src/agents/mcp.rs` (desktop
   status line that today expects `"rtok"`), `src/doctor/mcp_dupes.rs` (duplicate detection
   must not flag the two entries as dupes), README and `docs/agents-and-worktrees.md` (+ `ru`,
   `uk`).
4. Unit tests with temp homes; no real agents in tests. Live check in a Code-tab session
   (debug only).

Findings (live, 2026-10-24, Claude.app Code tab, `claude` 2.1.308):
- Both files register the server under the same name and command: `mcpServers.rtok` →
  `~/.ketch/bin/rtok mcp --host claude` in `claude_desktop_config.json` and in
  `~/.claude.json`.
- The session's own server runs (pid 41931, child of the session's `claude` 41497, cwd the
  project), but every `mcp__rtok__*` call of the session was served by pid 80493, the child of
  `Claude.app/Contents/Helpers/disclaimer` (`calls.session_id = mcp-80493`). The app hands its
  own servers to the session over the SDK, and on a name clash the app's server wins.
- That shared process happened to have cwd `apps/rtok`, so `whoami` linked by `cwd` to another
  live session in that folder (`host_session` not this one): a wrong link, worse than T455's
  `Outside`.
