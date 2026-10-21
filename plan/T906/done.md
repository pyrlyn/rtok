T672 made `expand` resolve an 8-character prefix and switched the other pointers to `names::short_id`. The read cache still printed the full 64-character id (`unchanged id …`, and `previous` / `expand` on a delta). T674 left that until T672 landed. T531 had required the full id because expand was an exact primary-key lookup; that constraint is gone.
The lines now print `short_id`. The measurement `ref_id` stays the full id. `expand` of the printed prefix returns the archived bytes.
Check: `dedup_reference_expands_to_the_archived_bytes` — the printed id is 8 hex chars, CLI and MCP expand return the file, and the ledger `ref_id` is the 64-char id.
Result: `dedup_line` and the delta pointer in `src/plugins/read/cache.rs` use `names::short_id`. The read plugin README matches.
