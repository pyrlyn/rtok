Source-confirmed: [run_with](src/plugins/graph/index.rs#L113) clears stale symbol rows only when `!dry_run`, then reads the existing stats unconditionally. With an old stored fingerprint and unchanged indexed files, dry-run takes the stat skip gate and reports no new rows; an actual run clears those stats and parses the same files. This violates the dry-run's would-be indexing report independently of T542's missing configuration input.

Execution plan:

1. Seed an indexed fixture and a deliberately obsolete fingerprint. Snapshot the store, run dry-run, then run the actual index on the same fixture.
2. When the fingerprint is stale, bypass old stat/SHA reuse for the simulated run without deleting rows or changing metadata.
3. Compare predicted indexed/inserted counts with the real cold run and assert the dry-run left symbols, fingerprint, timestamps and rank unchanged; retain warm dry-run behavior.

Check: stale-fingerprint dry-run predicts the real reindex and performs no writes; `just check` passes. Source-derived reproduction.

Result: A stale fingerprint ignores stored stats in dry runs too, so the dry-run report predicts the real cold reindex.

Check: `a_stale_fingerprint_dry_run_predicts_the_cold_reindex` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
