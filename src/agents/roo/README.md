# Roo Code

`rtok agents install roo` — Roo Code, the VS Code extension forked from Cline.
One file, the global `mcp_settings.json`, gets `mcpServers.rtok` → `rtok mcp` as
`{command, args}` with no `type`. An empty `[setup.roo] mcp_path` means
`<Code user dir>/globalStorage/rooveterinaryinc.roo-cline/settings/mcp_settings.json`
(the `vscode` host's `code_user_dir` when set, otherwise the OS default). A
project `.roo/mcp.json` overrides a same-named global server, so setup does not
write it. `roo-cline.customStoragePath` moves the settings directory; point
`mcp_path` there when that setting is in use. Foreign servers survive installs
and removes.

No CLI variant: the `roo` CLI exists, and no MCP path of its own is documented.
No hook links: Roo's docs describe the two MCP files, not shell hook events.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Roo Code's docs describe mcp_settings.json and .roo/mcp.json, not shell hook events |
| mcp | yes | `mcpServers.rtok` → `rtok mcp --host roo` in the global `mcp_settings.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | Roo Code has no documented model base-URL file; roo-cline.debugProxy is a debug proxy, not the model endpoint |
| plugin | no | Roo Code has no documented local plugin directory to link; modes live in the extension |

`register_mcp` / `unregister_mcp` call [`super::register_stdio_mcp`](../mod.rs),
the helper Cline and Windsurf use for the same JSON — no second copy of the
entry.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP (`mcp_settings.json` and `.roo/mcp.json`, `mcpServers.<name>` stdio `command` / `args`, no `type`): https://docs.roocode.com/features/mcp/using-mcp-in-roo
- CLI exists (`roo`, NDJSON stdin) with no documented MCP file of its own: https://docs.roocode.com/update-notes/v3.50.0
- Extension id `RooVeterinaryInc.roo-cline` (`src/package.json`, globalStorage folder is the lowercased id): https://github.com/RooCodeInc/Roo-Code/blob/main/src/package.json
- Settings directory (`<globalStorage>/settings/mcp_settings.json`; `customStoragePath` replaces the base): https://github.com/RooCodeInc/Roo-Code/blob/main/src/utils/storage.ts
