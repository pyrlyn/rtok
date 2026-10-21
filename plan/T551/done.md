Source-confirmed: [docs_query](src/plugins/docs/mod.rs#L138) sets before_bytes from the full documentation bodies of all considered hits, but est_before at line 154 estimates only their already-truncated snippets. est_after estimates the returned JSON. For one long document with a short snippet, most of the reduction is absent from token accounting and JSON metadata can make the reported token saving negative while bytes show a large reduction. T455 requires a query Measurement; this card fixes the inconsistent before basis rather than adding another row.

Execution plan:

1. Seed a long cached document with a small snippet limit and query it under a fixed estimator. Assert before_bytes and est_before describe the same full set of considered bodies, and after values describe the actual response.
2. Obtain full-body token estimates through the existing store/runtime boundary before snippet and output-budget trimming; avoid duplicating the query Measurement.
3. Cover multiple hits, budget-trimmed hits and no-result queries; retain the documented definition of considered content and the current query output.

Check: token and byte baselines refer to the same pre-trim content, exactly one row records a returned query result, and `just check` passes. Source-derived accounting defect; no measured saving is claimed by this audit.

Result: Docs-query savings are estimated against the same full content the byte accounting uses.

Check: T551 test in `src/plugins/docs/mod.rs` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
