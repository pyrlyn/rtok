# OpenHands

`rtok agents install openhands` — the `openhands` CLI. One file, the user-level
`~/.openhands/mcp.json`, gets `mcpServers.rtok` → `rtok mcp` as `{command, args}`
(OpenHands documents no `type` for stdio servers). `[setup.openhands] mcp_path`
moves the file. Servers added with `openhands mcp add` and any other entry
survive installs and removes.

No hook links: OpenHands documents hooks only as a project `.openhands/hooks.json`,
which belongs to the project and is never written by setup.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | OpenHands documents hooks only as a project `.openhands/hooks.json`; no user-level file is documented |
| mcp | yes | `mcpServers.rtok` → `rtok mcp --host openhands` in `~/.openhands/mcp.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | rtok has not checked OpenHands's LLM base-URL setting, so it writes none |
| plugin | no | rtok ships no OpenHands plugin |

`register_mcp` / `unregister_mcp` call [`super::register_stdio_mcp`](../mod.rs),
the helper Cline, Roo Code, Windsurf and Droid use for the same JSON — no second
copy of the entry.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP (`~/.openhands/mcp.json`, `mcpServers`, stdio `command` / `args` / `env`): https://docs.openhands.dev/openhands/usage/cli/mcp-servers
- Hooks (project `.openhands/hooks.json`): https://docs.openhands.dev/openhands/usage/customization/hooks
- Install (`uv tool install openhands`, the `openhands` binary): https://docs.openhands.dev/openhands/usage/cli/installation
