# Agent notes — `docs`

**Owns** `src/plugins/docs/**`, `src/store/docs.rs`, `migrations/0034_docs_fts/up.sql`.

**Contract**: `Plugin` and `Ctx` come from `rtok_plugin_sdk`. Storage is `Store::replace_docs` / `search_docs` / `get_doc` / `doc_cached`.

**Invariants**
- Default off. No hook methods. No network except `rtok docs fetch`.
- No `Authorization` header and no context7.com URL.
- A failed fetch does not delete the previous `doc_crates` row.
- `docs_query` returns snippets and ids. `docs_get` returns one body.
- A reply that contains snippets records `Measurement { plugin: "docs", kind: "query" }`.
- Tool descriptions sum to ≤ 60 estimated tokens.
- FTS queries go through `fts_phrase_query`. Schema changes are a new migration.

**Checks**: plan.md T471.
