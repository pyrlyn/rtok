Source-confirmed on `0b14b88e`: [`expand::run`](src/expand.rs#L200) and [`mcp::expand_text`](src/mcp.rs#L837) validate ranges against `usize::MAX`, call `fetch`, and only then validate against the actual line count. [`fetch`](src/expand.rs#L26) commits the expanded flag and its Measurement. Expanding a one-line live-zone archive with `lines: "2-3"` therefore returns an error after permanently freezing the compression decision and charging an expansion that returned no bytes. The earlier past-EOF fix correctly rejects the range; this task addresses its remaining side effects, not range syntax (T522).

Execution plan:

1. Seed an unexpanded decision and a one-line archive, request `2-3` through both CLI and MCP, and assert an error with unchanged decision and measurement rows.
2. Separate retrieval/validation from successful-expansion bookkeeping; share the sequence across both surfaces and preserve the atomic freeze-plus-measurement transaction.
3. Cover empty archives and valid filtered expansion, and retain malformed-range checks.

Check: all failed ranges leave state unchanged; successful expansion still freezes once and records the intended cost; `just check` passes.

Result: `expand::fetch_range(cx, id, lines)` checks the range against the payload's real line count between the archive lookup and the freeze-plus-Measurement transaction (`mark_expanded_recorded`, unchanged). `expand::run` and the MCP `expand_text` both call it; `fetch` is `fetch_range(.., None)`. The syntax check before the lookup stays. An empty archive rejects every range.

Check: `a_range_past_the_end_neither_freezes_nor_charges` (one-line and empty archive, CLI and shared fetch) and `expand_text_range_past_the_end_leaves_the_decision_unfrozen` failed with the check disabled and pass with it, and a following valid range freezes once and records one `expand` row; `expand` 43/43, `mcp` 64/64; clippy clean.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
