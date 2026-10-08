# `json_tree`

Fold a nested JSON tree into hoisted values and repeated element bodies. Off by default;
a block is rewritten only when the fold estimates fewer tokens than the JSON it replaces.

| | |
|---|---|
| Surfaces | proxy `compress` mode; MCP tool results |
| Spec | the `spec (replaces)` column of the catalogue in `plan.md` §1 |
| Default | **off** |

## Mechanism

Object and array values used by two or more objects are printed once under `VARS:`. An object
body that appears twice, ignoring `id` and `name`, becomes an `EL-` template; occurrences
print `template=EL-…` plus their children. A uniform scalar table is left for `toon`. The
original is archived; `rtok expand <id>` returns those bytes. MCP `read` and `search`
results are not folded.

## Config

```toml
[plugins.json_tree]
enabled = false
```

## Tasks

See `roadmap.md` § `json_tree`. Checks in `plan.md`.

## Status

Off until a `Measurement` row (`plugin: "json_tree"`, `kind: "fold"`) shows a saving.
