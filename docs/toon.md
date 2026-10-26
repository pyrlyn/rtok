# toon plugin

Port of `src/plugins/toon`. Proxy filter (and MCP surface): an old tool result whose text is a JSON array
of uniform scalar objects (≥ 3 keys, ≥ `min_rows` rows) becomes

```
[toon <id8>]
[N]{a,b,c}:
  1,x,true
```

only when pointer + TOON estimates fewer tokens than the JSON. The original text is archived and the
decision persisted per `tool_use_id`, so replays are byte-identical and an expanded id stays original.
Cells that would read back as another type (empty, delimiters, padding, `123`, `true`, `null`) are quoted.
Never touches the last `[plugins.archive] keep_turns` turns, nor blocks `archive`/`json_tree` decided.

## Enable / disable
```toml
[plugins.toon]
enabled = true
min_rows = 5
```

## Test
`./gradlew :rtok:macosArm64Test` — `ToonTest` (12; the decode oracle lives in the test, as in Rust),
`AnthropicWireTest` (2) for the Anthropic Messages view the proxy filter walks.

## Limitations
- Runs inside the proxy (Phase 7); until then it is exercised through the wire directly.
- Number cells print the JSON literal as received; Rust re-formats non-integers through f64
  (`-1.5e3` → `-1500.0`).
- The `expand` → `toon/expand` measurement row is checked with `rtok expand` (Phase 6).
