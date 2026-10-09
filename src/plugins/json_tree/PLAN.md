# json_tree — design note (D15)

## Problem

`archive` runs before any structural encoder and, past `plugins.archive.min_tokens`, replaces a large tool result with a head/tail pointer. `rtok mcp -- <server>` does the same by line count. A design or AST JSON therefore never reaches an encoder that can hoist repeated values and element bodies, and the model sees a pointer.

## Alternatives

| Tool | Version | Date | Gets right | Gets wrong |
|------|---------|------|------------|------------|
| Figma-Context-MCP | main @ 2026-10-08 | 2026-10-08 | count-gated hoist and `EL-` element templates for a repeated design tree | MIT TypeScript, Figma-specific; not vendored (D6) |
| jq | 1.7 | 2026-10-08 | lossless compact JSON, models already know it | no hoist of values shared by many nodes |
| TOON (toon-format) | 2026-09-01 | 2026-09-01 | uniform scalar tables shrink a lot | nested objects stay JSON; `toon` already owns that case |

## Mechanism

Walk the JSON. A value used by two or more objects is hoisted into a `VARS:` line (sha1, 8 hex, lengthened by 4 on a clash). An object body that repeats, ignoring identity keys `id` and `name`, becomes `EL-<sha1-8>`; a body that is only a type-like field is not templated. One line per node. The original is archived first, and the block is rewritten only when the folded form estimates fewer tokens. `read` and `search` results are left to the line cut.

The property that beats the table: repeated structure is named once, and `expand <id>` still returns the pre-fold bytes.

## Rejected

- Copying the TypeScript sources — D6; the idea is reimplemented here.
- Default-on before a `Measurement` row shows a saving.
- Folding a uniform scalar table — `toon` already encodes those.
- A Figma client, image download, or telemetry — out of scope for a JSON fold.

Target: a nested JSON tool result is folded only when the folded form estimates fewer tokens than the original, and expand returns those original bytes.

Falsified by: a uniform scalar table is folded, or `expand` of the archive id is not the pre-fold JSON.
