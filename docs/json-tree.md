# json_tree plugin

Port of `src/plugins/json_tree`. Folds a large nested JSON tool result (≥ 256 bytes, with a nested
object, not a uniform table) into:

```
[json-tree <id8>]
VARS:
V-1a2b3c4d: {"color":"#C0FFEE","type":"SOLID"}
ELEMENTS:
EL-5e6f7a8b: {"fills":V-1a2b3c4d,"type":"FRAME"}
[FRAME] #root name=Screen note=…
  [FRAME] #child-1 name="Card 1" template=EL-5e6f7a8b
```

Values seen twice become `V-` (SHA-1 content ids, lengthened by 4 hex on a clash), repeated node bodies
become `EL-` templates; key order never changes the text (canonical JSON with byte-ordered keys). A string
shaped like an id is always quoted, so it never reads as a reference. Only used when the fold estimates
fewer tokens; the original is archived and the decision persisted, like `archive`/`toon`.

## Enable / disable
```toml
[plugins.json_tree]
enabled = false   # off by default
```

## Test
`./gradlew :rtok:macosArm64Test` — `JsonTreeTest` (12, including the 32-case determinism check that
replaces the Rust proptest with a seeded generator; no new dependency). SHA-1 lives in `rtok-sys`
(`Sha1`, 2 vectors in the test).

## Limitations
- The MCP `shorten_result` path (3 Rust tests) waits for `cmd`/`mcp`.
- Non-integer numbers print as received (Rust re-formats them through f64).
