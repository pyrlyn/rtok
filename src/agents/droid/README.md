# Factory Droid

`rtok agents install droid` — Factory's `droid` CLI. One file, the user-level
`~/.factory/mcp.json`, gets `mcpServers.rtok` → `rtok mcp` as `{command, args}`
(`type` is optional for stdio servers). `[setup.droid] mcp_path` moves the file.
Project `.factory/mcp.json` files belong to the project, so setup does not write
them. Foreign servers survive installs and removes.

No hook links yet: Droid documents Claude-shaped hook events, but its tool names
and `PreToolUse` output have no checked mapping in rtok. No Desktop variant: no
Droid desktop app is documented.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Droid documents hooks, but rtok has no checked mapping of its tool names and PreToolUse output yet |
| mcp | yes | `mcpServers.rtok` → `rtok mcp --host droid` in `~/.factory/mcp.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | Droid has no single model base-URL setting; each customModels entry carries its own baseUrl |
| plugin | no | Droid plugins install into its own cache through `droid plugin install`; rtok ships no Droid plugin |

`register_mcp` / `unregister_mcp` call [`super::register_stdio_mcp`](../mod.rs),
the helper Cline, Roo Code and Windsurf use for the same JSON — no second copy of
the entry.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP (`~/.factory/mcp.json`, `mcpServers`, stdio `command` / `args` / `env`, `type` optional for stdio): https://docs.factory.com/cli/configuration/mcp
- Hooks (events, `~/.factory/hooks.json`, exit codes): https://docs.factory.com/cli/configuration/hooks-guide
- Plugins (`droid plugin install`, `.factory-plugin/plugin.json`): https://docs.factory.com/cli/configuration/plugins
- Custom models (`customModels[].baseUrl` in `~/.factory/settings.json`): https://docs.factory.com/cli/configuration/byok
