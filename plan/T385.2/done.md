optimization.md §2.2 L2. One table decides, per lane: compress/archive, `toon`, `tools_rewrite`, `context_management`, semantic cache, Flex, routing, upstream, timeout. Defaults: rewrites only on `agent`; `batch` and `files` always pass through.

Check: bulk and batch request bodies byte-identical in `compress` mode; agent behaviour unchanged; `just check`.

Result: `[proxy.lanes.bulk|embeddings|meta|internal]` carry `compress`, `toon`, `tools_rewrite`, `context_management`, `semantic_cache` (all off) and `timeout_s` (0 = `proxy.timeout_s`). `Lane::policy` and `Lane::passes_through` (`src/proxy/lane.rs`) feed `shape_request`, `compress`, `context_edits`, `rewrite_tools`, the semantic-cache lookup and store, and a per-lane read-timeout client. A lane switch only narrows its global switch (global AND lane). `agent` has no table: the global switches decide as before, and a test shows its upstream bytes equal the lanes-off path. `batch` and `files` have no keys and are never rewritten, `stream_options` shaping included. Flex, routing and per-lane upstream join the table with T385.5, T385.10 and T385.7. Tests: `tests/proxy_lane_policy.rs` and unit tests in `lane.rs`; trycmd snapshots and `docs/config.md` (en, ru, uk) updated. `cargo nextest run --workspace` ended with 2713 of 2714 passed; the one failure (`hook_fail_open a_locked_session_end_is_deferred_not_lost`, a timing assertion under load) passes alone. #825.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
