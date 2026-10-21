Found in the 2026-09-22 audit: 40 `read`/`expand`/`search` results carried `path outside cwd: …` as plain text without `is_error` (the flag is set only for the other 77 failures), so the model may treat the refusal as file content. Timeouts read `Error: Error: Request timed out` (doubled prefix), and `read` rejects a range the model quoted, `"975-1015"`, with `invalid line range`.

Plan: in `src/mcp.rs` map every tool `Err` (including the root guard in `src/plugins/read/mod.rs:202`) to `is_error: true` with one `Error:` prefix; strip surrounding quotes in the line-range parser.

Check: unit tests for an outside-cwd read (`is_error` true), a quoted range (accepted) and the error text (one prefix); `just test` green.

Result: `parse_range` (shared by `read` and `expand`) strips one matching pair of surrounding quotes, so `"975-1015"` parses like the bare range. `tools/call` and `--call` map a tool `Err` through one `invoke_text` helper. On current main the root guard already answers `isError: true` and no site adds an `Error:` prefix; the audit's doubled prefix was host-side. Tests `read_outside_cwd_sets_is_error`, `read_accepts_a_quoted_range`, `failed_call_text_never_doubles_the_error_prefix` and `parse_range_strips_surrounding_quotes` pin all three.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
