# docs

Exact crate versions from `Cargo.lock`, snippets from a local cache of docs.rs rustdoc JSON.

Surfaces: MCP `docs_resolve`, `docs_query`, `docs_get`. CLI: `rtok docs fetch <name>`.

Off by default (`[plugins.docs] enabled = false`). Fetch is the only network call and sends no API key. A miss prints `not cached: rtok docs fetch <name>` and does not download.

`llms.txt` / `llms-full.txt` in the working directory are indexed when `docs_query` is called with name `llms`.
