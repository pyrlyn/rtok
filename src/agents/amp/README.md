# Amp

`rtok agents install amp` — Sourcegraph's `amp` CLI. One file, the user settings
`~/.config/amp/settings.json`, gets `amp.mcpServers.rtok` → `rtok mcp` as
`{command, args}`; `amp.mcpServers` is one dotted top-level key, not a nested
`amp` object. The file is edited as JSONC, so comments survive. A `settings.jsonc`
that is the only settings file Amp has is edited in place, the way `mimo` treats
`mimocode.jsonc`. `[setup.amp] settings_path` moves the file. Workspace
`.amp/settings.json` files belong to the project, so setup does not write them.
Foreign servers survive installs and removes.

No hook links and no plugin: Amp has no shell hook events, and its documented
plugin API (`.ts` / `.js` files in `~/.config/amp/plugins`, events `tool.call`,
`tool.result`, `agent.start`) has no rtok plugin yet.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Amp has no shell hook events; its plugin events (tool.call, tool.result) are TypeScript handlers |
| mcp | yes | `amp.mcpServers.rtok` → `rtok mcp --host amp` in `~/.config/amp/settings.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | amp.url is Amp's own server URL, not a model-provider base URL |
| plugin | no | Amp loads .ts and .js plugins from its plugins directory, but rtok ships no plugin for its API yet |

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP (`amp.mcpServers` in `~/.config/amp/settings.json`, workspace `.amp/settings.json`, `amp mcp add`): https://ampcode.com/docs/customize/mcp
- Settings (file locations, `.jsonc`, `amp.url`): https://ampcode.com/docs/cli/settings
- Plugins (`~/.config/amp/plugins`, `.ts` / `.js`): https://ampcode.com/docs/customize/plugins
- Plugin API (`tool.call`, `tool.result` events): https://ampcode.com/docs/plugin-api
