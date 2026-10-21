**T29.2 implement optional embed search** · T29.1 · `memory` / `graph` plugin sources
Do: implement the embed backend behind the flag. Progressive disclosure / existing MCP tool names stay. A fixture note is indexed so both FTS5 and embed find it.
Check: flag off → FTS5-only bytes/behaviour; flag on → the fixture note is returned by both search paths.
Complexity: 4/5
Status: done 2026-09-12 · Model: Composer 2.5
Check: `cargo test --test p29_memory` (2 passed); `cargo test --lib plugins::memory store::embed::tests` green. Flag off keeps FTS5-only ranking; embed on finds `p29-gate-arctic-tern` via vector leg, hybrid RRF, and FTS `Diesel sync`; records `p29_hybrid_recall` Measurement. Deviation: >200 LOC / >3 files — `src/store/embed.rs` (BLOB KNN not sqlite-vec vec0), `migrations/0012.sql`, `tests/p29_memory.rs`, `tests/fixtures/p29_memory.toml`, `src/mcp.rs` wiring; deterministic `hash_embed`/`hash_embed_note` (no ONNX).
