# MiMo Code

`rtok agents install mimo` — MiMo Code, Xiaomi's terminal coding agent (`mimo`), an OpenCode
fork. One config file, `[setup.mimo] config_path` (default `~/.config/mimocode/mimocode.json`,
`MIMOCODE_HOME`/`MIMOCODE_CONFIG` move it), holds providers, models, tools, permissions and
MCP servers; the plugin lands beside it:

- Config: `[setup.mimo] config_path`, default `~/.config/mimocode/mimocode.json`
- Plugin: `<config dir>/plugins/rtok.ts`

MiMo accepts `mimocode.json` and `mimocode.jsonc` in its global dir and merges them, the
`.jsonc` last. MiMo itself writes a starter `mimocode.jsonc`, so when `mimocode.json` is absent
and `mimocode.jsonc` is there, setup edits the `.jsonc`: one entry is added or removed in place
and every comment and other byte stays (T520). When both exist, `mimocode.json` stays the
target. A `config_path` with another file name is used as it is. No Desktop variant: MiMo
Desktop exists (early access) but no config path for it is documented (T521).

## Modules

| Module | Support | Why |
| --- | --- | --- |
| mcp | yes | `mcp.rtok` → `{type: "local", command: [rtok, mcp, --host, mimo], enabled: true}` — the same local-server shape OpenCode kept from upstream (off with `[setup] mcp = false`) |
| plugin | yes | links `plugins/opencode/rtok.ts` to `<config dir>/plugins/rtok.ts` by default, once MiMo Code itself is detected; a stale or foreign destination is never overwritten |
| hooks | no | MiMo Code has no shell hook events; the linked plugin filters bash output instead |
| proxy | no | MiMo Code has no documented base-URL override; MIMOCODE_HOME/MIMOCODE_CONFIG relocate config, not the model endpoint |

`register_mcp`/`unregister_mcp` call `rtok-agent-sdk` through
[`super::register_local_mcp`](../mod.rs), the helper `opencode` was refactored onto for the
same JSON shape (T186) — no duplicated logic between the two forks; the `.jsonc` file goes
through the surgical editor in [`jsonc.rs`](../jsonc.rs) that Zed shares.

Plugin link (D21): MiMo loads every `*.ts` / `*.js` in `<config dir>/{plugin,plugins}/`, and
`plugins/opencode/rtok.ts` imports only `node:child_process`, so the OpenCode plugin is reused
as is, as Kilo does. `@mimo-ai/plugin` has the same `tool.execute.before` and
`tool.execute.after` hooks, and a file plugin needs `export default { id, server }`, which the
file exports. The plugin and the MCP entry are two capabilities, not two paths to one: the
plugin rewrites bash to `rtok run -- '…'` (skills still go through `rtok filter`), the MCP
serves `read`/`search`/`memory`/`graph`. The plugin reports as `--host opencode`. A missing
`rtok` fails open (output unchanged) and prints the ketch install line once.

## rtok plugins this host reaches

MCP carries `mcp`, and the linked plugin carries the bash call path (`cli`). Nothing carries
`hook` or `proxy`.

Reachable: measure, cmd, read, json_tree, archive, guard, memory, graph, toon, docs
Not reachable: proxy, inject, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- Repository and install (binary `mimo`, npm `@mimo-ai/cli`): https://github.com/XiaomiMiMo/MiMo-Code
- Docs home: https://mimo.xiaomi.com/mimocode/start
- Config files (`~/.config/mimocode/mimocode.json`, `MIMOCODE_HOME`/`MIMOCODE_CONFIG`, project `.mimocode/mimocode.json`): https://mimo.xiaomi.com/mimocode/config-files
- MCP servers (`mcp.<name>`, `type: "local"`/`"remote"`, `command`/`args`/`enabled`): https://mimo.xiaomi.com/mimocode/mcp-servers
- Config overrides (global dir files `config.json`, `mimocode.json`, `mimocode.jsonc` merged in that order; project vs. global): https://mimo.xiaomi.com/mimocode/config-overrides
- Environment variables (no base-URL override documented): https://mimo.xiaomi.com/mimocode/env-vars
- Plugin package (`tool.execute.before`, `tool.execute.after`, `shell.env`; npm `@mimo-ai/plugin`): https://github.com/XiaomiMiMo/MiMo-Code/tree/main/packages/plugin
- Plugin loader (`{plugin,plugins}/*.{ts,js}` in each config dir): https://github.com/XiaomiMiMo/MiMo-Code/blob/main/packages/cli/src/config/plugin.ts
