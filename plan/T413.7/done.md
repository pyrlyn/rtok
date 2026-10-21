VS Code / JetBrains extension and the `cn` CLI. MCP and rules; variants per documented config location.

Done: `src/agents/continue/` writes the `rtok` item of `mcpServers` (stdio: `name`, `type`, `command`, `args`) into `~/.continue/config.yaml` as a text-level list-item edit validated with the existing `serde-saphyr`; a missing file is created with the required `name`, `version`, `schema`; `[setup.continue] config_path`; variants Continue CLI (`cn`) and Continue (extensions, detected through VS Code). The line-level YAML helpers Goose already had moved to `src/agents/yaml_text.rs` and both hosts use them. No rules, hooks, proxy or plugin: none has a documented file. Research in `research.md` §32.7.

Check: unit tests for the list edit (create, append, flush-indent list, empty and flow values, CRLF, foreign YAML, extra item keys, declined remove), the real-config round trip, `agents_doc`, `host_docs`, trycmd snapshots; `just check`.
