The open plan.md row T460 is a different bug (Windows path compare). This record is the `mem_pack` work on this branch.

MCP `mem_pack` packs FTS hits into one answer under a token budget. Each hit starts at abstract (title and snippet). Leftover budget deepens the best hits to the first paragraph, then the body. A tier that does not fit is skipped whole. `limit` is 1–20 (default 8) and `max_tokens` is 1–2000 (default 400); `search_limit` does not cap this tool. The hook index is unchanged. A `Measurement` row (`plugin: memory`, `kind: mem_pack`) records the placed bodies against the packed text. No model call.

Check: `plugins::memory::pack` unit tests (abstracts fit, a huge body stays abstract, leftover budget deepens only the first hit, identical title and snippet collapse, an over-budget estimator returns nothing); `memory_pack_returns_a_tier_and_records`; `tests/trycmd/mcp.toml` lists `mem_pack` after `mem_search`.

Result: pack tests 5 passed; `memory_pack_returns_a_tier_and_records` passed; `mcp_surface_stays_within_sixty_description_tokens` passed (the six descriptions sum to 60). `cli_trycmd` lists `mem_pack`; the report counts 31 tools, ~810 description tokens. `cargo clippy --workspace --all-targets --all-features --exclude rtok-wasm-demo-guest -- -D warnings` clean.
