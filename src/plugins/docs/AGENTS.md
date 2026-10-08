# Agent notes — `docs`

**Owns** `src/plugins/docs/**`, `src/store/docs.rs`, migration `0034_docs_fts`.

**Contract**: import `rtok_plugin_sdk::…`, not `crate::plugin`.

**Invariants**
- Default off. No hook methods; SessionStart / PreCompact / prompt_submit stay untouched.
- No Context7 client, OAuth, telemetry, trust scores, embeddings, website crawl, or `rustdoc-types`.
- `docs_resolve` / `docs_query` / `docs_get` never open a socket. `rtok docs fetch` is the only GET.
- `docs_resolve` reads `Cargo.lock` with the `toml` crate; it does not spawn `cargo`.
- Unknown crate: one-line miss (`not in Cargo.lock:` / `not cached:`), not an error exit.
- Store SQL only in `src/store/docs.rs` (D13). FTS MATCH uses `fts_phrase_query` like `search_notes`.
- A query that returns snippets records `plugin: "docs"`, `kind: "query"`. No saving claim without that row.
- Tools listed in `mcp_tools()` are routed in `src/mcp.rs` `invoke`.

**Checks**: `plan.md` T455.
