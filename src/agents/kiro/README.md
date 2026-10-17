# Kiro

`rtok agents install kiro` — the Kiro IDE and the `kiro-cli` CLI. One file, the
user-level `~/.kiro/settings/mcp.json`, gets `mcpServers.rtok` → `rtok mcp` as
`{command, args}`. Kiro's MCP page states no per-surface path, so both variants edit
that file and the IDE variant is detection only (like Kilo). `[setup.kiro] mcp_path`
moves the file. Another Kiro page lists `~/.kiro/mcp.json` for the same scope; point
`mcp_path` there if a build reads that one. Project `.kiro/settings/mcp.json` files
belong to the project, so setup does not write them. Foreign servers survive installs
and removes.

No hook links: Kiro's hook files (`.kiro/hooks/`) are documented, but the page states
no exit-code or output-to-context rules for command hooks.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Kiro's hooks page does not state the exit-code or output-to-context rules of command hooks |
| mcp | yes | `mcpServers.rtok` → `rtok mcp --host kiro` in `~/.kiro/settings/mcp.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | Kiro documents no model base-URL setting |
| plugin | no | Kiro documents no local plugin directory to link |

`register_mcp` / `unregister_mcp` call [`super::register_stdio_mcp`](../mod.rs),
the helper Cline, Roo Code and Windsurf use for the same JSON — no second copy of
the entry.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP (`~/.kiro/settings/mcp.json`, `.kiro/settings/mcp.json`, `mcpServers`, loading priority): https://kiro.dev/docs/mcp/configuration/
- MCP registry page listing `~/.kiro/mcp.json` for the global scope: https://kiro.dev/docs/mcp/registry/
- Hooks (`.kiro/hooks/*.json`, `~/.kiro/hooks/`): https://kiro.dev/docs/hooks/
- CLI (`kiro-cli`): https://kiro.dev/docs/cli/
