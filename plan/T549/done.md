Source-confirmed: [ingest_llms](src/plugins/docs/mod.rs#L191) writes every workspace to the same `("llms", "local")` cache key. [docs_resolve/query](src/plugins/docs/mod.rs#L91) reads that key even when ingestion finds no local file. [replace_doc_crate](crates/rtok-store/src/docs.rs#L118) replaces all items with that name/version, with no workspace dimension. Query workspace A's llms.txt, then query `llms` in B with the same rtok home and no llms file: B receives A's documentation. If B has its own file, it overwrites A's cache. T455 introduced workspace docs but did not isolate their storage.

Execution plan:

1. Use two temporary workspace roots sharing one store: A has a unique local marker, B initially has no file and then a different marker. Assert neither resolve nor query in B exposes A's content.
2. Give local documents a canonical workspace namespace, consistently applied during ingestion, cache checks and queries; keep downloaded crate/version documents globally reusable.
3. Reconcile missing or empty local sources within their namespace and test workspace switching, deletion and recreation. Keep old unscoped local rows from being treated as the current workspace's docs.

Check: each workspace sees only its own local documents, absence reports an uncached/empty result according to the tool contract, shared crate caches still work, and `just check` passes. Reproduction is source-derived.

Result: Local llms docs are scoped to the workspace as `local:<canonical cwd>`.

Check: T549 test in `src/plugins/docs/mod.rs` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
