# Continue

`rtok agents install continue` — Continue, the VS Code and JetBrains extensions and the `cn`
CLI. All read one user file, `~/.continue/config.yaml`, and the `rtok` item of its
`mcpServers` list runs `rtok mcp` as a `stdio` server (`name`, `type`, `command`, `args`).
The file is YAML that users annotate, so the item is edited as text: only the list item named
`rtok` is added, replaced or removed, and every other byte stays. A file that is not valid
YAML, or whose `mcpServers` is not a block list, is an error and is left untouched. A file
that does not exist is created with the three properties the config reference requires
(`name`, `version`, `schema`) and the item. Keys a user adds to the item (`env`, `cwd`,
`connectionTimeout`) are not an edit; a changed `name`, `type`, `command` or `args` is.

`CONTINUE_GLOBAL_DIR` moves Continue's directory; set `[setup.continue] config_path` to
follow it. On Windows the default file is `%USERPROFILE%\.continue\config.yaml`. A workspace
`.continue/mcpServers/` folder is documented for a workspace only and is never written. A
Continue Hub assistant other than "Local Config" does not read this file.

`rtok agents status` reads this host through `installed`: its MCP status reader parses
JSON and TOML, not YAML.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Continue's docs describe config.yaml, mcpServers and rules, not shell hook events |
| mcp | yes | `mcpServers` item `rtok` → `rtok mcp --host continue` in `config.yaml` as a `stdio` server (off with `[setup] mcp = false`) |
| proxy | no | Continue sets a model's apiBase per model in config.yaml; there is no single endpoint file to point at the proxy |
| plugin | no | Continue has no documented local plugin directory to link; blocks and rules live in config.yaml and .continue/ |

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP servers (`mcpServers`, `stdio` fields, workspace `.continue/mcpServers/`): https://docs.continue.dev/customize/deep-dives/mcp
- `config.yaml` reference (required `name`, `version`, `schema`; `mcpServers` properties): https://docs.continue.dev/reference
- `config.yaml` location (`~/.continue/config.yaml`, `%USERPROFILE%\.continue\config.yaml`): https://docs.continue.dev/customize/deep-dives/configuration
- `cn` configuration (default `~/.continue/config.yaml`): https://docs.continue.dev/cli/configuration
