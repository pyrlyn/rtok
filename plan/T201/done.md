Found 2026-09-22 in the core pass: hook stdin is `read_to_end` with no cap and parsed whole (`src/hooks/mod.rs:22-23`); `insert_call_io` → `spill` (`src/store/mod.rs:541-563`) sha256s over-cap bodies even though the hook path passes `archive_dir = None` (the hash feeds only a metadata column); `guard::post_tool` (`src/plugins/guard/mod.rs:71-77, 330-339`) sha256s and writes the entire tool response to the archive dir synchronously on every cached Read/Bash. A 20 MB PostToolUse payload costs two full passes plus the JSON DOM per event — the ≤ 10 ms budget breaks deterministically per MB (T178 family).

Plan: skip `hex_sha256` in `spill` when `archive_dir` is `None` and the body is over cap (store NULL sha); bound the stdin read (`Take` at a `core.hook_max_input_bytes`, above which the hook fails open to `{}`); defer or cap `guard`'s archive write over a size threshold.

Check: `oversized_hook_call_io_does_not_archive` extended — over-cap bodies record NULL `request_sha256`/`response_sha256`; `spill_over_cap_without_archive_dir_skips_hashing`; latency gate with a 5 MB PostToolUse fixture dispatches < 50 ms; `just test` green.

Result: `rtok hook` reads stdin through `Take` at the new `core.hook_max_input_bytes` (8 MiB). Over it, the hook fails open to `{}` with one stderr line, before any JSON parse. `spill` skips the sha256 when `archive_dir` is `None` (the hook path), so both sha columns stay NULL. `guard::post_tool` does not archive a body over 256 KiB and clears that key instead, so a stale smaller result never answers the repeat. Tests: `oversized_hook_call_io_does_not_archive` (extended), `spill_over_cap_without_archive_dir_skips_hashing`, `post_tool_over_cap_skips_archive_and_the_repeat_is_allowed`, `hook_dispatches_a_5mb_post_tool_body_under_50ms` (release-only like the other latency gates).

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
