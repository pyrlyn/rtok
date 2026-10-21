Warp terminal's agent. MCP and rules if they live in a file setup can edit; a setting stored only in the app's database is out of scope.

Check: the T413 per-host check (step 5) for this host.

Plan: Warp docs for a file-based MCP or rules path. If the setting lives only in the app database, close with that finding and no installer writes.

Done: `src/agents/warp/` (`mod.rs`, `README.md`): one Desktop variant, detected by the macOS bundle `/Applications/Warp.app` (Windows and Linux install paths and a `--version` flag are undocumented, so no bin). `mcpServers.rtok` → `rtok mcp --host warp` as `{command, args}` in `~/.warp/.mcp.json` through `register_stdio_mcp` (the helper Cline, Roo Code, Windsurf and Droid share); `[setup.warp] mcp_path` moves it. Hooks, proxy and plugin stay `Support::No` (nothing documented). Servers added in Warp's app settings and project `.warp/.mcp.json` files are never written. Registered in `HOSTS` and `host()`, `config/default.toml`, `docs/config.md`, `docs/agents.md` (and `ru`/`uk`), `agents_install` matrix, list/real-config/host-docs tests and trycmd goldens. Research in `research.md` §32.12.

Check: `agents_doc`, `host_docs`, `agents_install`, `agents_list_content`, `agents_real_config`, `config_coverage`, `cli_trycmd` and the `warp` unit tests green; `just check` green. Manual: `rtok agents install warp --dry-run` on this machine (Warp.app installed here) names `~/.warp/.mcp.json` and `mcpServers.rtok: rtok mcp --host warp` and writes nothing.
