41 Bash `rtok expand <id>` calls in about 20 sessions (2026-09-22..10-17) got `[rtok <id> · identical to a result N turns ago · expand: rtok expand <id>]` instead of the content. A bare `rtok expand` is never wrapped (`never_wrap`), but `rtok run` wraps a compound command that holds one (`x; rtok expand id`, `cd x && rtok expand id | sed`), and its dedup treated the expanded output as a repeat; the formatters could also cut it.

Done means: the output of `rtok expand` (CLI and MCP) is never replaced by a dedup or shortening pointer.

Result: `emit_filtered_to` in `src/plugins/cmd/run.rs` finds an `rtok expand` stage with `bounded::stages` (`runs_rtok_expand`) and prints the body raw: no dedup, no shortening, no pointer, no archive. MCP `expand` never goes through `identical_result` and the Cursor MCP shortening skips it, so it needed no change. Fail-first test: `rtok_expand_output_is_never_deduped_or_shortened`.

Status: done 2026-10-20
Model: Claude Code / claude-sonnet-5-5
