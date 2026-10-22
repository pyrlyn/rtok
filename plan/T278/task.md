---
status: in progress
priority: P1
complexity: 3
readiness: 90%
agent: Claude Code / claude-opus-5-5
---

# T278. `rtok agents info <agent>` reports the real MCP state, not "mcp installed" by assumption

Problem: `agents info claude` shows `mcp installed` for Claude Desktop with no `rtok` entry in `claude_desktop_config.json`, because `installed` (`claude/mod.rs:688-694`) returns `mcp` when the Code plugin is present (`code_serves_mcp`), and matches the text `"rtok"` anywhere in the file. That hid today's bug. Other hosts do the same when a plugin is installed (`files.contains("mcp") || plugin`).

Fix:
1. Status comes from `rtok_mcp::status` (T277), or from an equivalent function in `src/agents/mod.rs` if T278 lands first. It parses the config and looks up the exact key path and server name; no substring match.
2. `agents info` prints MCP per surface on its own line, for example `mcp  desktop  missing (claude_desktop_config.json has no "rtok")`, `mcp  cli  plugin rtok@rtok (serves "rtok")`, `mcp  cli  entry ~/.claude.json`. A stale entry (wrong command or args) prints `stale` and the difference. Hooks keep their current line.
3. A plugin only counts for the surface it actually serves. The Claude Code plugin never makes Desktop show as installed.
4. `--json` and the web UI model (`web/model.rs`) carry the same fields: `surface`, `entry` (`present`, `missing`, `stale`), `plugin`.
5. `agents list` and `installed_hosts` use the same status, so "installed" means the same thing everywhere.

Tests: a table-driven `Vfs` test per host writes each combination (no entry, entry, stale entry, plugin only, entry and plugin) and asserts the printed and `--json` status matches the file content; a regression test for today's case (Code plugin installed, Desktop file without `rtok`) expects `desktop missing`.

Check: on the creator's machine, before T275 is fixed `rtok agents info claude` shows Desktop `missing`; after `rtok agents install claude` it shows `present`; `just check`.

Execution (2026-09-27): one PR off main. Status comes from `rtok_mcp::status` (T277, merged in #437) per surface. `agents info`, `agents list`, `installed_hosts` and `web/model.rs` all read it, so a plugin only counts for the surface it serves. Tests: one table-driven `Vfs` test per host, plus the regression "Code plugin installed, Desktop file without `rtok`" → `desktop missing`. The T275 per-host PRs still open (VS Code, ZCode, Kimi, Grok) touch the same host modules, and whichever lands second rebases.
Progress (2026-09-28): merged in #464 (per-surface `mcp` lines, `--json` and web `mcp: [{surface, file, entry, diff?, plugin}]`, table-driven test over every host, the `desktop missing` regression). Left: the creator-machine Check.
Progress (2026-10-21, agent audit on `main` `3ccd3a2`): the fix still holds. `installed_modules`, `installed_hosts`, `agents info`, `agents list`, `--json`, the web model (`src/model/mod.rs:576`) and `doctor` all read `mcp::rows`. `claude/mod.rs:910` checks Desktop with `has_entry` only and never through `code_serves_mcp`, which no longer exists. One gap was found and fixed: Continue, Goose (both YAML) and Reasonix (`[[plugins]]` TOML) were added after #464. They write an MCP entry that `rtok_mcp` cannot parse, so they had no `mcp` surface line. `mcp::rows` now falls back to the host's own exact parser for them (`present`/`missing`, no stale state). A guard test fails when another MCP host has no row source. The Check did not run: this session's permissions blocked running any `rtok` binary. T275 is fixed in code, so the "before T275" half can no longer be seen. Re-run protocol for the creator: (1) `jq '.mcpServers.rtok' "$HOME/Library/Application Support/Claude/claude_desktop_config.json"`. If it prints `null`, `rtok agents info claude` must show `mcp     desktop  missing (claude_desktop_config.json has no "rtok")` even with the Code plugin installed. (2) Run `rtok agents install claude --desktop`, then `rtok agents info claude`, which shows `mcp     desktop  entry ~/Library/…/claude_desktop_config.json`. (3) `rtok agents info claude --json` carries `mcp: [{surface, file, entry, plugin}]` for `cli` and `desktop`, and both values match `jq`. When all three pass, the card moves to `done.md`.
