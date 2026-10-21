Goose (Block, now under the Agentic AI Foundation), CLI and desktop. MCP-native, so the minimum is the MCP entry; hooks only if documented.

Done: `src/agents/goose/` writes `extensions.rtok` (stdio: `name`, `type`, `cmd`, `args`, `enabled`) into `config.yaml` as a text-level block edit validated with the existing `serde-saphyr`, so comments and every other byte stay; keys Goose adds on re-save are not an edit; `[setup.goose] config_path`; variants Goose CLI and Goose (Desktop). Hooks (Open Plugins, no adapter), proxy (env var only) and plugin report a factual reason. Research in `research.md` §32.6.

Check: unit tests for the block edit (add, replace, remove, CRLF, foreign YAML, resave defaults), the real-config round trip, `agents_doc`, `host_docs`, trycmd snapshots; `just check`.
