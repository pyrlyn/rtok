Found in the 2026-09-22 audit: every Claude Code session lists both `mcp__rtok__*` and `mcp__plugin_rtok_rtok__*` (700+ deferred-tool listings in 7 days); only `mcp__rtok__*` is ever called (854 calls, 0 on the plugin name). `rtok doctor` shows `mcp ✓ installed` and `plugin ✓ installed` for `claude (cli)` at once. Two registrations break the D21 singleton and pay the tool descriptions twice.

Plan: the install half is done by T243 — the direct entry was the Claude Desktop `mcpServers.rtok` in `claude_desktop_config.json`, which the desktop app's Code tab loads next to the plugin; install now drops it while the plugin is installed. Left: make doctor flag the pair (plugin installed + an rtok entry in `claude_desktop_config.json` or `~/.claude.json`) as a duplicate.

Execution: `doctor::mcp_duplicate_lines(plugin, files)` — pure over the parsed files, so the test needs no host disk (D29); `page()` feeds it `plugin_installed` and the two files (`[doctor] claude_json`, `claude::desktop_path()`); each file with `mcpServers.rtok` next to the plugin is one `duplicate:` line under `overlaps`, naming the file and `rtok agents install claude` (which strips it, T243). Unit test in `src/doctor.rs`.

Check: doctor reports a duplicate on a fixture that has both; `tests/agents_doc.rs` re-blessed if the host table changes; `just test` green.

Result: `rtok doctor` now lists, under `overlaps`, one `duplicate:` line per file that still registers `mcpServers.rtok` while the Claude plugin (`rtok@rtok`) is installed — `~/.claude.json` (`[doctor] claude_json`) and `claude_desktop_config.json` (`claude::desktop_path()`) — naming the file and `rtok agents install claude`, which strips the entry under the plugin (T243). The check is a pure `mcp_duplicate_lines(plugin, files)` over the parsed files; no host table changed, so `docs/agents.md` needed no re-bless. Test: `doctor::tests::mcp_entry_next_to_the_claude_plugin_is_a_duplicate` (one file, both files, no plugin, no entry). `just check` green (1733 tests).

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
