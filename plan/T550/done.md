Source-confirmed: every [ingest_llms](src/plugins/docs/mod.rs#L200) call calculates a SHA but unconditionally calls replace_doc_crate. That method deletes and reinserts the items; [doc_items](crates/rtok-store/migrations/0039_docs_fts/up.sql#L12) uses generated integer primary keys. Seed one local section (id 1), fetch a crate whose item gets id 2, then run docs_resolve or docs_query with the unchanged local file present: the local section is recreated at id 3. [docs_get](src/plugins/docs/mod.rs#L177) on the previously returned id 1 now fails. An unrelated read invalidates the handle even though its source bytes did not change. This is distinct from T549's cross-workspace namespace.

Execution plan:

1. Add the interleaved local-ingest/crate-fetch/local-resolve fixture, retaining the id from the first local query and calling docs_get afterward.
2. Compare the current source SHA and scope with cached metadata and make unchanged ingestion a no-op, with the check/write sequence safe against concurrent ingestion.
3. Verify repeated resolves and unrelated crate queries preserve local item ids and FTS rows; changed bytes must still refresh the affected document. Coordinate the cache key with T549.

Check: docs_get keeps resolving ids from unchanged local documents across repeated reads, actual edits are searchable, and `just check` passes. Source-derived reproduction; generated-id behavior is visible in the schema and replacement path.

Result: Re-ingesting unchanged local docs keeps their doc ids, via `sync_doc_crate`.

Check: T550 test in `src/plugins/docs/mod.rs` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
