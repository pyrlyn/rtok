Source-confirmed: [run_changed](src/plugins/graph/index.rs#L238) removes deleted files' symbol rows but refreshes the derived rank document only when `report.inserted > 0` (line 290). Replacing the last definition with an empty file also reports zero inserted symbols: [replace_one](crates/rtok-store/src/symbols.rs#L234) deletes old symbols, writes a freshness sentinel and returns zero. [run_with](src/plugins/graph/index.rs#L192) has the same zero-symbol replacement gap: its later missing-file deletion count is zero because the path remains in the keep set. [rank::map/render](src/plugins/graph/rank.rs#L221) renders definitions from the stored document without checking current symbols or files. Index a sole `a.rs` defining `old`, then delete it via the incremental path or empty it and reindex: the map still lists `a.rs: old`. A later unchanged full scan does not repair the already-existing rank document. The symbols themselves are removed correctly; this is not T324's rejected stale-symbol candidate.

Execution plan:

1. Seed symbols and a rank document; separately test an incremental deletion and full/incremental replacement with zero extracted rows.
2. Track whether index contents changed independently of the number of inserted rows, and refresh or invalidate the derived document after successful removals/replacements.
3. Assert the rendered map and stored rank paths/definitions drop obsolete entries immediately; retain warm no-change behavior and the existing rank-rebuild failure fallback.

Check: the map never serves the removed definition after a successful update, subsequent warm indexing stays consistent, and `just check` passes. Source-derived reproduction; no full index runtime was executed during the audit.

Result: The rank document is refreshed after any replacement, removal or stale-fingerprint run, not only when rows were inserted.

Check: `rank_document_drops_removed_definitions` (3 cases) failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
