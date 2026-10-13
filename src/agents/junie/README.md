# Junie CLI

`rtok agents install junie` — JetBrains Junie's CLI (`junie`). One file, the
user `mcp.json`, gets `mcpServers.rtok` → `rtok mcp` as `{command, args}` with
no `type`. An empty `[setup.junie] mcp_path` means `~/.junie/mcp/mcp.json`. The
project file `.junie/mcp/mcp.json` is meant to be committed with the repository,
so setup does not write it. The docs name no variable that moves `~/.junie`; set
`mcp_path` to relocate the file. Foreign servers survive installs and removes.

No IDE variant: the IDE plugin's MCP page describes a settings dialog and names
no file. No hook links: the only documented hook event is `SessionStart`.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Junie documents only a SessionStart hook, and rtok's hooks need tool-use events |
| mcp | yes | `mcpServers.rtok` → `rtok mcp --host junie` in `~/.junie/mcp/mcp.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | Junie has no documented model base-URL file |
| plugin | no | Junie has no documented local plugin directory to link |

`register_mcp` / `unregister_mcp` call [`super::register_stdio_mcp`](../mod.rs),
the helper Cline, Roo Code and Windsurf use for the same JSON — no second copy
of the entry.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP files and schema (`~/.junie/mcp/mcp.json`, `.junie/mcp/mcp.json`, `mcpServers.<name>` stdio `command` / `args` / `env`, no `type`): https://junie.jetbrains.com/docs/junie-cli-mcp-configuration.html
- Config files and the trust rule for project config: https://junie.jetbrains.com/docs/junie-cli-configuration.html
- Hooks (`SessionStart` only): https://junie.jetbrains.com/docs/junie-cli-hooks.html
- IDE plugin MCP settings (a dialog, no file path): https://junie.jetbrains.com/docs/junie-plugin-mcp-settings.html
