# Reasonix

`rtok agents install reasonix` — the `reasonix` CLI (and its `dsnix` shim). One
file, the global `~/.reasonix/config.toml`, gets one `[[plugins]]` table
(`name = "rtok"`, `command`, `args = ["mcp", "--host", "reasonix"]`). `[setup.reasonix] dir`
moves the Reasonix home (Windows keeps it in `%APPDATA%\reasonix`; `REASONIX_HOME` moves it).
Comments, providers and other servers survive installs and removes. Project `reasonix.toml`
and `.mcp.json` files belong to the project, so setup does not write them.

No Desktop variant: the desktop app shares the same config, but no install path of it is
documented. No hook links yet: Reasonix hooks live in `settings.json` with their own payload and
tool names, and rtok has no checked mapping for them.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Reasonix hooks (settings.json) use their own event payload and tool names, which rtok has no checked mapping for |
| mcp | yes | `[[plugins]] name = "rtok"` → `rtok mcp --host reasonix` in `~/.reasonix/config.toml` (off with `[setup] mcp = false`) |
| proxy | no | Reasonix has no single endpoint setting; each [[providers]] entry carries its own base_url |
| plugin | no | Reasonix plugin packages are its own format; rtok ships no Reasonix plugin |

The `[[plugins]]` array is keyed by `name`, so this host edits it with `toml_edit` itself
instead of the table-keyed helpers Codex and Grok use.

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- Config paths (`<Reasonix home>/config.toml`, `REASONIX_HOME`, `[[plugins]]` example): https://github.com/esengine/DeepSeek-Reasonix/blob/studio/docs/CONFIG_PATHS.md
- Plugins (MCP): `[[plugins]]` `name` / `command` / `args` / `env` / `type`: https://github.com/esengine/DeepSeek-Reasonix/blob/studio/docs/GUIDE.md#plugins-mcp
- Hooks (`<Reasonix home>/settings.json`): https://github.com/esengine/DeepSeek-Reasonix/blob/studio/docs/GUIDE.md#hooks
- Install (`npm install -g reasonix`): https://github.com/esengine/DeepSeek-Reasonix#install
