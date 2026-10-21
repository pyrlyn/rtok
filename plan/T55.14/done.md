**T55.14 Semantic-cache key drops tool_result content** · P3, 1/5 · `src/proxy/semantic_cache.rs`

From review 2026-09-17, second pass (reproduced, then fixed). `content_text` kept only `text` fields, so a `tool_result` block contributed nothing to `CachePrompt` — neither to the direct hash nor to `scope_hash`: two requests differing only in tool-result text hashed identically (both eligible under defaults).

Do: one `block_text` helper per content block — text as-is; `tool_result {id}: {nested content}`; `tool_use {id} {name}`; `image`/`document` source data and OpenAI `image_url` contribute their sha256 — used by `content_text` for both the direct hash and the scope.
Check: `tool_result_text_joins_the_cache_key` (the two repro bodies hash differently, shape stays eligible), `tool_use_ids_join_the_cache_key`, `image_blocks_hash_into_the_cache_key`; the `only_near_identical_prompts_clear_the_threshold` family, `p9_fixture_audit_zero_false_hits` and both proxy semantic-cache e2e tests unchanged and green.
Status: done 2026-09-17 · Model: ZCode / GLM-5.3
Evidence: `just check` green — 844/844 tests, fmt, clippy `-D warnings`, build-min, jscpd.
Deviation: OpenAI Chat `tool_call_id`s (the wire's analogue of Anthropic `tool_use_id`) are not folded separately — its tool results already join as `role: "tool"` message text, which is what the card's repro shape needed.
