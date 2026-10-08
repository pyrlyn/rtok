# docs — design note (D15)

Survey date: **2026-10-08**. Task **T471**.

## Problem

Agents answer library questions from training data or from a hosted docs API that needs an API key and a network ranker. The bytes that matter are the crate version already pinned in `Cargo.lock` and the rustdoc for that version. Sending the question to a third-party ranker spends a request and can leak the query.

## Alternatives

| Tool | Version | Date | Gets right | Gets wrong |
|------|---------|------|------------|------------|
| Context7 MCP (`@upstash/context7-mcp`) | 4.0.3 | 2026-10-08 | Two tools: resolve a library id, then query snippets for one version string | Ranker, crawler and corpus are private; client requires `CONTEXT7_API_KEY` or a low anonymous limit; no lockfile read; tool descriptions are hundreds of tokens |
| docs-mcp (mmgeorge) | 0.1.1 | 2026-10-08 | Fetches docs.rs rustdoc JSON with no API key and caches it | A separate MCP process; rtok would shell out (D6) |
| cargo-aidoc (`aidoc-core`) | crates.io | 2026-10-08 | Turns rustdoc JSON into `llms.txt` sections | Pins a nightly `rustdoc-types` format; generates files rather than answering one question under a token cap |

## Mechanism

`docs_resolve` reads `Cargo.lock` and reports the exact version plus whether that version is cached. `docs_query` searches SQLite FTS5 (the same quoted-phrase BM25 as notes) and returns ids, paths and short snippets under `plugins.docs.max_tokens`. `docs_get` loads one body. `rtok docs fetch` is the only network call: `GET https://docs.rs/crate/<name>/<version>/json.gz` with no `Authorization` header, stored under `~/.rtok/docs` after the gzip decodes. A failed fetch leaves the previous rows in place.

Target: a `docs_query` reply stays within `plugins.docs.max_tokens` (default 800) and the three tool descriptions sum to at most 60 estimated tokens.

Falsified by: a query that returns snippets without a `docs`/`query` `Measurement` row, or a cache miss that opens a socket.

## Rejected

- Calling context7.com — API key and a hosted ranker.
- Vendoring docs-mcp or cargo-aidoc — D6; the plugin is written here.
- `rustdoc-types` — the JSON format version moves; a `serde_json::Value` walk of `paths` and `index` is enough.
- Embeddings — FTS5 already answers identifier queries; `[plugins.memory.embed]` stays off.
