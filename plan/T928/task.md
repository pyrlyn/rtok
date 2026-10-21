---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T928. MCP input schemas mark aliased params required, so T353's aliases never reach the server

`prepare_args` (`src/mcp.rs`) maps `query`→`pattern`, `file_path`→`path`, `hash`→`id`, but
`search` requires `pattern`, `read` requires `path` and `expand` requires `id`
(`src/plugins/read/mod.rs`, `src/mcp.rs`), so Claude Code rejects the call first with
`-32602 Input validation error`: 28+ `search {"query"}` calls in 20 sessions (18 after T353),
plus `read {"file_path"}`, `expand {"hash"}`, `symbol {"query"}`. `ping` requires `agent`
although the code accepts none (6 sessions).

Done when: every alias `prepare_args` accepts passes schema validation (e.g. `anyOf` of the
alias sets, or the alias listed and `required` dropped where an alias exists), and `ping`'s
`agent` is optional.

Check: a test that validates each alias call against the advertised schema with a JSON-schema
validator; `just check`.
