OpenHands CLI (the local variant only; the cloud service is out of scope). MCP and any documented hooks.

Check: the T413 per-host check (step 5) for this host.

Plan: OpenHands local CLI docs for the MCP file. Cloud config stays out. Hooks only with a documented payload.

Done: `src/agents/openhands/` (`mod.rs`, `README.md`): one CLI variant, the `openhands` binary on PATH. `mcpServers.rtok` -> `rtok mcp --host openhands` as `{command, args}` in `~/.openhands/mcp.json` through `register_stdio_mcp` (the helper Cline, Roo Code, Windsurf and Droid share); `[setup.openhands] mcp_path` moves it. Hooks, proxy and plugin stay `Support::No`: the docs describe hooks only as a project `.openhands/hooks.json`, which belongs to the project. Servers added with `openhands mcp add` are never touched. Registered in `HOSTS` and `host()`, `config/default.toml`, `docs/config.md`, `docs/agents.md` (and `ru`/`uk`), `agents_install` matrix, list/real-config/host-docs tests and trycmd goldens. Research in `research.md` §32.14.

Check: `agents_doc`, `host_docs`, `agents_install`, `agents_list_content`, `agents_real_config`, `config_coverage`, `cli_trycmd` and the `openhands` unit tests green.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
