**T45.2 Proxy cache hits and errors keep usage rows** · P0, 3/5 · `src/proxy/mod.rs`, `src/proxy/semantic_cache.rs`, `tests/proxy.rs`
Do: every proxied request owes one `usage` row (T5.1). A semantic-cache hit now writes the usage and provider token rows with the request bytes, beside its measurement and `call_io`; an upstream error or a non-2xx / no-usage response writes a minimal usage row instead of none.
Check: `tests/proxy.rs::proxy_cache_hit_records_usage_and_request_bytes`, `proxy_upstream_error_still_records_usage_row`.
Status: done 2026-09-17 · Model: OpenCode / Muse Spark 1.3 (code); closed by Claude Code / Fable 5.1
Evidence: the code landed inside `a423aca`; `just check` exit 0 after T47.4 (716 passed), which runs `tests/proxy.rs`.
Deviation: committed by another agent's snapshot commit (`a423aca`), not as its own `T45.2` commit; this commit only moves the task to done.md.
