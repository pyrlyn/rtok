**T70.4 Cursor plugin shortens MCP results the host launched** · T59.4 · `src/mcp/wrap.rs`, `src/hooks/{types,mod}.rs`, `src/agents/cursor/mod.rs`, `plugins/cursor/hooks/hooks.json`
Do: Cursor `postToolUse` (verified 2026-09-18: input `tool_output`; output `updated_mcp_tool_output` replaces MCP results only; `afterMCPExecution` / `afterShellExecution` have no documented replacement) maps onto `rtok hook PostToolUse --host cursor`. Long MCP `content[].text` is shortened through T59.4 `shorten_result`, lossless `expand <id>` trailer, `Measurement { plugin = "archive", kind = "mcp" }`. Skip `mcp_server_name == rtok` and tool `expand`. Fail open; never rewrite the call.
Check: `cursor_mcp_post_tool_use_shortens_only_foreign_long_results`, `shorten_result_records_archive_mcp_above_threshold`, `post_tool_use_shortens_long_mcp_results_and_skips_small_and_rtok`; `tests/host_docs.rs` green; host table unchanged (no bless).
Complexity: 3/5 — one wrap helper, Cursor field map, one replacement stdout shape.
Status: done 2026-09-18
Check result: wrap/hooks/cursor lib tests and `cursor_plugin` / `host_docs` / `agents_doc` / `mcp_wrap` green. `just check` red only on a pre-existing trycmd bash-completion snapshot (`--context` from T67.2), not this hook.
Model: Cursor / grok 4.6
