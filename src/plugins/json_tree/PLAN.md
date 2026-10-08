# json_tree — design note (D15)

## Problem

Nested JSON tool results (design trees, config dumps, repeated component props) are one object copied many times. `archive` then replaces the whole block with a head/tail pointer, so the model loses the structure. `toon` only encodes uniform scalar tables. A repeated fill or a repeated node body is still paid for on every copy.

## Alternatives

| Tool | Version | Date | Gets right | Gets wrong |
|------|---------|------|------------|------------|
| figma-developer-mcp | 0.13.2 | 2026-10-08 | count-gated style hoist and element templates, then positional lines | TypeScript server, no `expand`, telemetry on by default, no byte gate |
| Figma Dev Mode MCP | remote 2026-10-08 | 2026-10-08 | official design-to-code tools | not a fold for arbitrary JSON tool results; needs a Figma seat |
| toon (rtok) | in-tree | 2026-10-08 | uniform scalar tables, lossless archive | refuses nested objects |
| minified JSON | RFC 8259 | 2026-10-08 | lossless, models already read it | repeated objects stay repeated |

## Mechanism

Hoist a non-empty object or array used as a field value by two or more objects (never `children`). Template an object body, minus `id` / `name` / `children`, when that body occurs twice and has more than one key. Print a `VARS` / `ELEMENTS` / `NODES` tree. Ids are sha256, 8 hex, lengthened by 4 on collision. Archive the original first. Rewrite only when the pointer plus the fold estimates fewer tokens than the block. Uniform scalar tables are left for `toon`.

The property that beats the table: the fold is lossless (`expand` returns the archived bytes) and it runs before `archive`, so a nested tree stays readable instead of collapsing to a head/tail pointer.

## Rejected

- Folding uniform scalar tables — `toon` already encodes those, and a second encoder would fight over the same `tool_use_id`.
- Default-on — there is no corpus `Measurement` yet, and a fold that does not shrink must not rewrite.
- Porting the TypeScript server — MIT would allow a copy only with its copyright notice; the algorithm is small enough to reimplement, and the server's telemetry and image download are out of scope.

Target: fold only when the estimate shrinks

Falsified by: a uniform scalar table is folded or expand returns different bytes
