**T36.10 the two documented output caps exist** · — · `src/expand.rs`, `src/mcp.rs`, `src/config/mod.rs`
Do: `expand.max_lines` and `mcp.max_result_chars` are declared, documented and read by nothing. Apply both in one shared line-slicing helper used by `expand::run` and the MCP `expand` tool (they already duplicate `take(b).skip(a-1)`).
Check: `[expand] max_lines = 100` truncates a larger payload and the output says so; the MCP result honours `max_result_chars`.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test --lib max_lines` — 1 passed (`max_lines_truncates_and_reports`); `mise exec -- cargo test --lib max_result_chars` — 2 passed (`expand_honours_max_result_chars`); T36.2 `live_zone_pointer` and T36.9 `finish()` retained.
