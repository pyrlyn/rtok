# Agent notes — `json_tree`

**Owns** `src/plugins/json_tree/**`.

**Contract**: the `Plugin` trait, `Ctx` and the host capabilities come from the published
`rtok-plugin-sdk` crate (`crates/rtok-plugin-sdk`), not from `crate::plugin` — import them as
`rtok_plugin_sdk::…`.

**Invariants**
- Lossless: the original JSON is archived before the block is rewritten; `expand <id>` returns those bytes.
- `fold_json` returns `None` for a value under 256 bytes, a value with no nested object, and any value `toon::tabular_keys` accepts.
- Rewrite only when the folded form estimates fewer tokens. No saving claim without a `Measurement` row.
- `default_on` is false. `archive` and `toon` leave a `[json-tree ` pointer alone.
- MCP folds only when the plugin is enabled, the tool is not `read` or `search`, and the fold fits `max_lines` and is smaller.
- No new crate dependency, no Figma client, no image download, no telemetry. The TypeScript sources are not copied.

**Checks**: `plan.md` T455. Order: `roadmap.md` § `json_tree`.
