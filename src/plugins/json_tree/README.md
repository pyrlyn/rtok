# `json_tree`

Nested JSON with repeated objects → a positional tree. Off by default. A block is
rewritten only when the fold estimates fewer tokens than the JSON it replaces.

| | |
|---|---|
| Surfaces | proxy `compress` mode; MCP tool results (not `read` / `search`) |
| Default | **off** |

## Mechanism

Field values that are non-empty objects or arrays and occur at least twice are hoisted
(`v_<sha256>`). Object bodies that repeat, ignoring `id`, `name`, and `children`, become
templates (`EL-<sha256>`). The model sees `VARS`, `ELEMENTS`, and `NODES`. Uniform scalar
tables stay with `toon`. The original bytes are archived; `expand <id>` returns them.

## Config

```toml
[plugins.json_tree]
enabled = false
```

## Tasks

See `roadmap.md` § `json_tree`. T437.

## Status

Proxy and MCP fold. Default off, so request bytes stay identical until enabled.
