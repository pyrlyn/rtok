Junie in JetBrains IDEs and its CLI if one is documented. MCP first.

Done: `src/agents/junie/` CLI-only, MCP-only host (`junie`). `mcpServers.rtok` → `rtok mcp --host junie` goes into `~/.junie/mcp/mcp.json` through the shared `register_stdio_mcp` / `unregister_stdio_mcp` (no second copy); `[setup.junie] mcp_path` overrides the file (the docs name no variable that moves `~/.junie`). The project `.junie/mcp/mcp.json` is committed with repositories, so setup never writes it. No IDE variant: the plugin's MCP page names no file. Hooks stay `Support::No`: the only documented hook event is `SessionStart`. Research in `research.md` §32.9 (checked 2026-10-12); host registered in `HOSTS`, `agent_for`, `mcp::rows`, `junk_map::NO_ROWS`, the config section and default, `docs/agents.md`, `docs/agents-and-worktrees.md`, `audit-coverage.md`, the trycmd snapshots and the host tests.

Check: `agents::junie` unit tests (dry run, idempotent stdio entry, foreign servers kept, `apply` via the trait), `agents_real_config` (`junie_keeps_the_real_mcp_json`), `host_docs`, `agents_list_content`, config-init / config-show / report-md snapshots.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
