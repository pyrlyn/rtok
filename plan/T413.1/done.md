VS Code extension forked from Cline. Landed on main in `46e7139b` (feat) with host registration fixed in #760 (`376e1645`).

Done: `src/agents/roo/` desktop-only host; MCP via shared `register_stdio_mcp`; empty `[setup.roo] mcp_path` derives the VS Code globalStorage file; docs and list/config tests updated. Research in `research.md` §32.1.

Check: host unit tests, `agents_doc`, `host_docs`, `agents_real_config` (as in the landing PR); #760 CI green on main.

Status: done 2026-10-05 (#760)
Model: Cursor / grok 4.7
