# Crush

`rtok agents install crush` — Charm's Crush terminal agent. One file, the global
`crush.json`, gets `mcp.rtok` → `rtok mcp` as `{command, args}`; `type` defaults to
`stdio` in Crush's schema, so the entry carries none. `[setup.crush] config_path`
defaults to `~/.config/crush/crush.json`. Crush reads `$CRUSH_GLOBAL_CONFIG/crush.json`
first and `$XDG_CONFIG_HOME/crush/crush.json` next; point `config_path` there when
either is set. Foreign keys and servers survive installs and removes.

Why JSON and not `crushrc`: Crush's current config is `crushrc`, a Bash script, and
`crush.json` is deprecated but still loaded and merged with it. A structured object lets
rtok add and remove only its own entry; a script would need marker lines rtok cannot
check. Crush logs a warning only when a `crush.json` and a `crushrc` in one directory
define the same top-level key (`mcp`), and still merges them.

No hook links: the only documented event is `PreToolUse`, and rtok has no checked
mapping of Crush's tool names (`bash`, `view`, ...).

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Crush documents only a PreToolUse hook, and rtok has no checked mapping of its tool names |
| mcp | yes | `mcp.rtok` → `rtok mcp --host crush` in the global `crush.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | Crush takes provider base URLs per provider entry; rtok has no checked single endpoint to point at the proxy |
| plugin | no | Crush has no documented local plugin directory to link; it loads skills, not plugins |

`register_mcp` / `unregister_mcp` call [`super::register_stdio_mcp_in`](../mod.rs) with
the `mcp` key, the same body Cline, Roo Code and Windsurf use under `mcpServers`.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- Config files, locations and the legacy JSON format: https://github.com/charmbracelet/crush/blob/main/docs/config/README.md
- Config schema (`mcp` object, `type` default `stdio`): https://github.com/charmbracelet/crush/blob/main/schema.json
- Hooks (`PreToolUse` only): https://github.com/charmbracelet/crush/blob/main/docs/hooks/README.md
- Global config path and the crush.json / crushrc warning: https://github.com/charmbracelet/crush/blob/main/internal/config/load.go
