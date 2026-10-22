Source-confirmed on `0b14b88e`: [`messages_text` / `block_text`](src/proxy/semantic_cache.rs#L368) flatten structured requests into strings before hashing. `tool_result.is_error`, message `name`, and `image_url.detail` are omitted. For example, otherwise identical single-user requests with the same image URL and `detail: "low"` versus `"high"` produce identical CachePrompts. A text block containing `tool_result t: OK` also has the same flattened text as `{type:"tool_result", tool_use_id:"t", content:"OK"}`. With semantic cache enabled, the direct tier returns the first response for the second request before an upstream call. This is distinct from completed T55.14/T212/T303/T323: those added fields but retained lossy flattening.

Validation: an offline Rust harness compiled verbatim `messages_text`, `content_text`, `block_text` and `canonical_json` from this revision; all four differing pairs above returned equal message fields. Other CachePrompt inputs were held equal. This proves the normalization collision, not an end-to-end proxy run.

Execution plan:

1. Add paired-request fixtures for image detail, tool-result error status, participant name and literal text versus typed tool content. Assert distinct direct keys and two upstream calls.
2. Build the direct key from canonical structured message/content values, keeping type tags, field boundaries and response-affecting fields. Keep semantic text extraction separate.
3. Retain key-order equivalence and the existing caller/model/tool-schema isolation tests; do not broaden cache eligibility.

Check: all differing fixture pairs miss the direct cache, exact replay still hits, reordered object keys retain the same key, and `just check` passes.

Result: `CachePrompt` gains `typed` (per message, a sha256 of the canonical JSON and a second one with its text blanked) and `system_typed`, so `is_error`, `name`, `image_url.detail` and block types reach the direct key. `cache_control` is dropped from those digests, so a marker that moves between turns does not split the key. The flattened text stays for the embedding. `scope_hash` keeps only the blanked-text digest of the last user message, so the semantic tier still matches on its words but not across a different image `detail` or block type.

Check: `typed_fields_join_the_cache_key` (image detail, tool-result error flag, participant name, literal text versus typed block) failed with `typed` left empty and passes with it: all four miss in both tiers, exact replay hits; `key_order_and_cache_control_do_not_split_the_key`; `proxy` lib tests 142/142; clippy clean.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
