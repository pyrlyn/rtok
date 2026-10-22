Source-confirmed: [extractor_fingerprint](src/plugins/graph/index.rs#L417) hashes compiled queries and INDEX_VERSION but no configured extension map. [run_with](src/plugins/graph/index.rs#L111) therefore reuses old stats after changing `plugins.graph.extensions`; its stat gate skips unchanged files. Even touching the file reaches the [SHA gate](src/plugins/graph/index.rs#L346), which returns Same before consulting the new grammar. Index `sample.tpl` containing `fn old() {}` with `tpl = "rust"`, change only the mapping to `tpl = "py"`, then index again: the old Rust definition survives. T35.5 covers extractor upgrades; T68.10 added configurable mappings without including them in freshness.

Execution plan:

1. Add a fixture that changes the mapping after an initial index, with unchanged source bytes; exercise both unchanged and changed mtime.
2. Include a deterministic representation of the effective extension-to-grammar mapping in index freshness and invalidate the affected cached rows before either skip gate. Reuse the same policy for full and incremental entry points.
3. Verify equivalent map insertion orders do not rebuild, while mapping changes re-extract and remove obsolete symbols; retain warm-index skipping.

Check: the mapped file's symbols match the current grammar after reindex without a source edit; unchanged configuration still skips; `just check` passes. Source-derived reproduction, not an executed integration test.

Result: The sorted extension map joins the extractor fingerprint (nothing added for an empty map, so existing roots do not reindex on upgrade); `run_changed` reparses files on a stale fingerprint.

Check: `remapping_an_extension_drops_the_old_grammars_rows` (untouched and touched), `the_fingerprint_follows_the_mapping_content`, `an_event_after_a_remap_reparses_the_file`; `plugins::graph` 253 green. Committed with T543/T546 (one function, shared test hunk).

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
