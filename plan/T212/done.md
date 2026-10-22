Found 2026-09-22 in the surfaces pass: `CachePrompt` / `canonical_hash` (`src/proxy/semantic_cache.rs:150-173, 311-315`) cover provider/model/system/messages and a tools fingerprint — but not `max_tokens`, `temperature`, `top_p`, `tool_choice`, `thinking`, stop sequences, and `tools_fingerprint` (:388-397) hashes tool *names* only. Requests differing only in generation params or tool schemas hash equal and the cached body is replayed — I-23's "a hit can be a wrong answer" (a temperature-0 extraction sharing an entry with a temperature-1 brainstorm for `ttl_s`). I-23 tracks the general false-hit risk; these key omissions are the concrete ones (the T55.14 fix covered tool_result text only).

Plan: fold all non-message request fields into `canonical_hash` (serialize the body minus `messages`/`stream`) and hash each tool's full definition in `tools_fingerprint`.

Check: `sampling_params_join_the_cache_key` and `tool_schemas_join_the_cache_key` — bodies differing only in `max_tokens`/`temperature`/tool schema hash differently; `p9_fixture_audit_zero_false_hits` green; `just test` green.

Result: `CachePrompt` gains `params`: every top-level request field except `messages`/`model`/`system`/`tools`/`stream`/`metadata`/`user`, key-sorted, so `max_tokens`, `temperature`, `top_p`, `tool_choice`, `thinking` and stop sequences join `canonical_hash`. `tools_fingerprint` hashes each tool's full canonicalized definition, not its name. Anthropic Messages and OpenAI Chat share the path; Responses never reaches the cache (`eligible`). The cache is in-memory, so old keys just go cold. Tests: `sampling_params_join_the_cache_key`, `tool_schemas_join_the_cache_key`; `p9_fixture_audit_zero_false_hits` green.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
