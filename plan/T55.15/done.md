**T55.15 `live_blobs` rewrites image/document payloads into invalid blocks** · T51.1 · `src/proxy/anthropic.rs`, `src/proxy/openai_chat.rs`, `src/plugins/archive/mod.rs`
Do: `live_blobs` yields text blocks only — `source.data` of `image`/`document` and `image_url.url` are never rewritten in place, so turning `[plugins.archive] live_blobs = true` cannot produce an invalid (400) request. Text blocks carrying `data:` URIs stay the T51.1 case.
Check: repro tests `image_source_data_is_never_rewritten` and `openai_image_url_is_never_rewritten` — the very same bytes shrink as a text block in the same turn while the binary field stays byte-identical through `rewrite_blobs`; the existing `live_blobs_*` suite passes untouched.
Complexity: 2/5 — two wire arms deleted, two repro tests.
Status: done 2026-09-18
Check result: archive filter 44/44 and proxy filter 72/72 green (new repro tests included); full `just check` green in the isolation worktree (HEAD + own files). clippy's `collapsible_if` on the new Chat arm fixed in the same pass.
Model: ZCode / GLM-5.3-Flash
