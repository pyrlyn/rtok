# Goose

`rtok agents install goose` — Goose (Block, now under the Agentic AI Foundation), the CLI
and the Desktop app. Both read one file, `config.yaml`, and `extensions.rtok` there runs
`rtok mcp` as a `stdio` extension (`name`, `type`, `cmd`, `args`, `enabled`). The file is
YAML that Goose rewrites and users annotate, so the entry is edited as text: only the
`rtok` block under `extensions:` is added, replaced or removed, and every other byte stays.
A file that is not valid YAML, or whose `extensions:` is not a block mapping, is an error
and is left untouched. Keys Goose adds when it re-saves the entry (`envs`, `env_keys`,
`timeout`) are not an edit; a changed `cmd`, `args`, `type` or `enabled` is.

`GOOSE_PATH_ROOT` moves Goose's config to `<root>/config/config.yaml`; set
`[setup.goose] config_path` to follow it. On Windows the default file is
`%APPDATA%\Block\goose\config\config.yaml`.

`rtok agents status` reads this host through `installed`: its MCP status reader parses
JSON and TOML, not YAML.

## Modules

| Module | Support | Why |
| --- | --- | --- |
| hooks | no | Goose runs hooks from Open Plugins directories with its own event names, tool names and decision shape; rtok has no adapter for that payload yet |
| mcp | yes | `extensions.rtok` → `rtok mcp --host goose` in `config.yaml` as a `stdio` extension (off with `[setup] mcp = false`) |
| proxy | no | Goose documents its provider endpoint as the GOOSE_PROVIDER__HOST environment variable only; there is no file to point at the proxy |
| plugin | no | Goose plugins are Open Plugins directories (plugin.json, hooks/hooks.json); rtok ships no bundle in that format yet |

## rtok plugins this host reaches

MCP carries `mcp`. Nothing carries `hook`, `cli` or `proxy`.

Reachable: read, json_tree, archive, memory, graph, toon, docs
Not reachable: measure, cmd, proxy, inject, guard, compress

## Docs

Host documentation setup writes against; re-check the links when this host changes.

- Config files (`config.yaml` paths, `extensions.<key>` fields, `stdio` and `streamable_http` types): https://github.com/aaif-goose/goose/blob/main/documentation/docs/guides/config-files.md
- Manual extension entry (`cmd`, `args`, `envs`, `type: stdio`, `timeout`): https://github.com/aaif-goose/goose/blob/main/documentation/docs/getting-started/using-extensions.md
- `GOOSE_PATH_ROOT` and `GOOSE_PROVIDER__HOST`: https://github.com/aaif-goose/goose/blob/main/documentation/docs/guides/environment-variables.md
- Hooks (Open Plugins `hooks/hooks.json`, event names, payload, decision protocol): https://github.com/aaif-goose/goose/blob/main/documentation/docs/guides/context-engineering/hooks.md
- Desktop app name (`Goose.app`, Homebrew cask `block-goose`): https://github.com/Homebrew/homebrew-cask/blob/master/Casks/b/block-goose.rb
