Merged as T455 (pyrlyn/rtok#897); renumbered to T475 because #872, opened earlier, also took T455.

`archive` runs before any structural encoder and, past `plugins.archive.min_tokens`, replaces a large tool result with a head/tail pointer. `rtok mcp -- <server>` does the same by line count. A design or AST JSON therefore never reaches an encoder that can hoist repeated values and element bodies.

Plan: plugin `json_tree` (Proxy, Mcp), `default_on` false, registered immediately before `archive`. `fold_json` returns `None` unless the value is an object or array of at least 256 bytes with a nested object, and `toon::tabular_keys(value, 1)` is `None` so tables stay with `toon`. Values used by two or more objects are hoisted into a `VARS:` block (sha1-8, lengthened on collision). An object body that repeats, ignoring identity keys `id` and `name`, becomes `EL-<sha1-8>`; a body that is only a type-like field is not templated. One line per node. The original is archived first; rewrite only when the folded form estimates fewer tokens. `archive` and `toon` leave a `[json-tree ` pointer alone. MCP `shorten_result` folds before the line cut when the fold fits `max_lines` and is smaller, and does not fold `read` or `search`.

Check: `just check`; `cargo test -p rtok json_tree -- --test-threads=8`. `rtok expand <id>` returns the pre-fold bytes. A block `toon` encodes still has a `[toon ` prefix and no `[json-tree ` prefix. No saving without a `Measurement` row (`plugin: "json_tree"`, `kind: "fold"`).

Result: `just check` 2981 passed, 6 skipped. `cargo test -p rtok json_tree -- --test-threads=8` 11 passed.
