Found by a bug-hunt pass over `src/proxy/**`. Three ways the opt-in response cache could answer a request with a response that was not its own:

1. The tee task stopped on a client disconnect or an upstream error (and capped `buf` at `MAX_BODY_BYTES`), then `finish` cached that partial body under a 2xx status. A body is now cached only when it arrived whole; `call_io`/usage rows are written as before.
2. `block_text` hashed only `/source/data` of image/document blocks, so `url`, `file_id` and `content` sources all hashed the empty string. The whole canonicalized `source` is hashed now.
3. The key ignored who asked: two API keys (or different `anthropic-beta`/`anthropic-version`) shared an entry. A sha256 of `x-api-key`, `authorization`, `x-goog-api-key`, `anthropic-version`, `anthropic-beta`, `openai-organization` now joins the key on lookup and store; the raw secret is never stored.

Check: new `non_base64_image_and_document_sources_join_the_cache_key`, `tests/proxy.rs` `proxy_cache_never_stores_a_truncated_body` and `proxy_cache_is_keyed_by_caller_identity` (all failed before the fix); `just check` green.

Status: done 2026-10-01
Model: Claude Code / sonnet (reviewed by Claude Code / claude-opus-5-5)
