# Augment

`rtok agents install augment` — Augment's `auggie` CLI. One file, the user-level
`~/.augment/settings.json`, gets `mcpServers.rtok` → `rtok mcp` as `{command, args}`
(the docs' settings examples omit `type` for stdio). `[setup.augment] settings_path` moves the
file. Project `.augment/` files belong to the project, so setup does not write
them. Foreign keys and servers survive installs and removes.

No hook links: Augment hooks must be script files (`.sh`, `.ps1`, `.cmd`, `.bat`)
and a `PreToolUse` hook can only deny, never rewrite the call. No Desktop variant:
the IDE extension keeps its servers in a settings panel with no documented file.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Augment hooks must be script files and PreToolUse can only deny (no updatedInput), so rtok cannot rewrite a call through them |
| mcp | yes | `mcpServers.rtok` → `rtok mcp --host augment` in `~/.augment/settings.json` as `{command, args}` (off with `[setup] mcp = false`) |
| proxy | no | Augment documents no model endpoint setting or bring-your-own key to point at the proxy |
| plugin | no | Auggie plugins come from marketplaces through `auggie plugin install`; rtok ships no Augment plugin |

`register_mcp` / `unregister_mcp` call [`super::register_stdio_mcp`](../mod.rs),
the helper Cline, Roo Code, Windsurf and Droid use for the same JSON — no second
copy of the entry.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- MCP (`~/.augment/settings.json`, `mcpServers`): https://docs.augmentcode.com/setup-augment/mcp
- CLI integrations (MCP servers in settings): https://docs.augmentcode.com/cli/integrations
- Hooks (script-path commands, PreToolUse deny only): https://docs.augmentcode.com/cli/hooks
- CLI reference (`auggie`, `plugin`): https://docs.augmentcode.com/cli/reference
