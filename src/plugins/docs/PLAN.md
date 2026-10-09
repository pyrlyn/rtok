# docs — design note (D15)

## Problem

Agents look up crate docs through a hosted MCP (Context7) that needs an API key and a network
round-trip on every query. rtok already knows the exact versions in `Cargo.lock` and can keep
a local rustdoc JSON cache.

## Alternatives

| Tool | Version / date | Gets right | Gets wrong for rtok |
|------|----------------|------------|---------------------|
| Context7 MCP | 2026 | resolve + query + library ids | hosted; API key; ranker not in the public repo; D6 forbids wrapping it |
| docs.rs rustdoc JSON | current | exact crate+version JSON.zst | must be fetched once and indexed locally |
| rustdoc-types crate | crates.io | typed rustdoc JSON | extra dep; T455 forbids it — walk `paths`/`index` with serde_json |

## Mechanism

`docs_resolve` reads `Cargo.lock`. `rtok docs fetch` GETs docs.rs JSON.zst once, stores bytes
under `~/.rtok/docs/`, inserts FTS5 rows. `docs_query` / `docs_get` read only SQLite. Default off;
hook path unchanged.

## Rejected

- Context7 client / OAuth / telemetry / trust scores / embeddings / crawling the web.
- `rustdoc-types` (T455 P2).
- Fetch on the query or hook path.

Target: three MCP tools, descriptions ≤ 60 tokens; query is offline once cached; hook output unchanged when off.
Falsified by: `docs_query` opening a socket, a hook-path change, or descriptions over 60 tokens.
