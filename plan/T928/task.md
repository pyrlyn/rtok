---
status: in progress
priority: P2
complexity: 2
readiness: 100%
agent: Grok / grok-4.7
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

Plan: advertise the aliases `prepare_args` already renames — `search` `query`→`pattern`,
`docs_query` `question`/`userQuery`→`query` — as `anyOf`, with no top-level `required` on the
aliased field (`docs_query` keeps `name`). `missing_required` passes when one `anyOf` branch
is present and still names the first branch when none are. `ping` drops `required: ["agent"]`.
`file_path`, `hash` and `symbol` `query` are not renamed today, so those schemas stay (`read`
of `query` still misses `path`).
