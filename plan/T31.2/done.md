**T31.2 implement opt-in semantic cache** · T31.1 · `src/proxy/` / proxy plugin
Do: implement the cache behind the flag. Off → identical proxy bytes to today. On → cache hits only under the documented threshold; record measurements.
Check: off → identical proxy bytes; on → documented false-hit rate runnable on the P9 set.
Complexity: 4/5
Status: done 2026-09-12 · Check: `cargo test --lib semantic_cache` (6 passed: `semantic_cache_disabled_proxy_bytes_identical`, `semantic_cache_enabled_direct_hit_skips_upstream`, `p9_fixture_audit_zero_false_hits`, `direct_hit_on_exact_replay`, `hash_backend_skips_semantic_tier`); P9 synthetic corpus `tests/fixtures/p9_semantic_cache_corpus/` — false-hit pairs **0**, semantic hit rate **0%** at threshold 0.99 (orthogonal fixture embeddings; `embed_backend = hash` keeps tier 2 off in production).
Model: Composer 2.5
Deviation: **451 LOC** `src/proxy/semantic_cache.rs` + **169 LOC** `src/proxy/mod.rs` + fixture corpus (exceeds ≤200 LOC / ≤3 files).
