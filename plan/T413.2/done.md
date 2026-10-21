CLI forked from Gemini CLI. Landed on main in `46e7139b` (feat) with host registration fixed in #760 (`376e1645`).

Done: `src/agents/qwen/` plus `plugins/qwen` hooks extension; MCP via `register_stdio_mcp` into `~/.qwen/settings.json`; Claude-named hooks with second timeouts via `claude::insert_ours` on qwen `hook_events` rows; docs and list/config tests updated. Research in `research.md` §32.2.

Check: targeted nextest filter from the landing PR; #760 CI green on main.

Status: done 2026-10-05 (#760)
Model: Cursor / grok 4.7
