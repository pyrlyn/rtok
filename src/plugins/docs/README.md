# `docs`

Local rustdoc for Cargo dependencies: resolve the exact version from `Cargo.lock`,
search a cached docs.rs JSON index, fetch only when asked.

| | |
|---|---|
| Surfaces | MCP `docs_resolve`, `docs_query`, `docs_get`; CLI `rtok docs fetch` |
| Spec | T455 — Context7-style resolve/query, written here (D6); no Context7 client |
| Default | **off** |

## Tools

- `docs_resolve(name)` — exact crate name and version from `Cargo.lock`; `cached` true when that version is in the store. A miss is one line, not an error.
- `docs_query(name, query, version?)` — FTS snippets for one cached crate version; ids, path, kind, snippet. Empty cache: `not cached: rtok docs fetch <name>`. No network.
- `docs_get(id)` — one cached item body.

`rtok docs fetch <name>` is the only network path: GET `https://docs.rs/crate/<name>/<version>/json.zst`, store under `~/.rtok/docs/`, index into SQLite FTS5.

A workspace `llms.txt` or `llms-full.txt` is split on `#` headings as crate `llms` version `local`.
