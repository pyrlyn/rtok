# Warp

`rtok agents install warp` — the Warp terminal's agent. One file, the user-level
`~/.warp/.mcp.json`, gets `mcpServers.rtok` → `rtok mcp` as `{command, args}` (no
`type` key is documented for local servers). Warp reads that file by default.
`[setup.warp] mcp_path` moves the file. A project `.warp/.mcp.json` belongs to the
repository, so setup does not write it, and servers added in the app's own MCP
settings live in Warp's database and are not touched. Foreign servers survive
installs and removes.

Detection is the macOS bundle `/Applications/Warp.app`: Warp documents no install
path for Windows or Linux and no `--version` for its `warp-terminal` command, so
those installs are not detected. No hook links: Warp's docs name no hook surface.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Warp documents no hook surface |
| mcp | yes | `mcpServers.rtok` → `rtok mcp --host warp` in `~/.warp/.mcp.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | Warp documents no setting that points its agent at another endpoint |
| plugin | no | Warp documents no plugin format; rtok ships no Warp plugin |

`register_mcp` / `unregister_mcp` call [`super::register_stdio_mcp`](../mod.rs),
the helper Cline, Roo Code, Windsurf and Droid use for the same JSON — no second
copy of the entry.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP (file-based servers, `~/.warp/.mcp.json`, `mcpServers`, `command` / `args` / `env` / `working_directory`): https://docs.warp.dev/agents/capabilities/mcp
- Installation (macOS Applications folder, `warp-terminal` on Linux): https://docs.warp.dev/getting-started/installation-and-setup
