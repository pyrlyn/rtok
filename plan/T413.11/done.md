Crush (Charm), open-source terminal agent. MCP and any documented hooks.

Done: `src/agents/crush/` (one CLI variant, `crush` on PATH): `mcp.rtok` → `rtok mcp --host crush` in the global `crush.json` as `{command, args}` (`type` defaults to `stdio`), through the new `register_stdio_mcp_in` / `unregister_stdio_mcp_in` (the existing stdio helper with the object key as a parameter, so no second copy of the entry). `[setup.crush] config_path` (default `~/.config/crush/crush.json`) is the override for `CRUSH_GLOBAL_CONFIG` / `XDG_CONFIG_HOME`. Hooks stay `Support::No`: the only documented event is `PreToolUse` and rtok has no checked mapping of Crush's tool names. Why JSON and not `crushrc`: see `research.md` §32.11. Host registered in `HOSTS`, `agent_for`, `mcp::rows`, `junk_map::NO_ROWS`, the config section and default, the docs tables (en, ru, uk) and the host tests.

Check: `agents::crush` unit tests (dry run, `type`-less stdio entry under `mcp`, idempotence, foreign keys kept byte-equal after remove, `apply` via the trait), `agents_real_config` (`crush_keeps_the_real_crush_json`), `agents_install`, `host_docs`, `agents_list_content`, `agents_doc`, config and trycmd snapshots.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
