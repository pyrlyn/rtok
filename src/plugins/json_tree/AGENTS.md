# Agent notes — `json_tree`

**Owns** `src/plugins/json_tree/**`.

**Contract**: the `Plugin` trait, `Ctx` and the host capabilities come from the published
`rtok-plugin-sdk` crate (`crates/rtok-plugin-sdk`), not from `crate::plugin` — import them as
`rtok_plugin_sdk::…`.

**Invariants**
- Default off. A disabled plugin leaves the request bytes identical.
- Lossless: archive the original bytes before any rewrite. `expand <id>` returns those bytes.
- Only nested JSON that `toon` refuses. A uniform scalar table (`tabular_keys`) is not folded.
- The live zone (`archive.keep_turns`) is untouched.
- `archive` and `toon` must skip the `[json-tree ` pointer. Dispatch stays before `archive`.
- Do not fold `read` or `search` on the MCP wrap path.
- Content ids are sha256 (the `sha2` crate already in the tree). Do not add sha1.

**Checks**: `plan.md` T437. Order: `roadmap.md` § `json_tree`.
