From the graymatter gap review (`research.md` §14). graymatter publishes a no-LLM benchmark (`go run ./benchmarks/token_count`, keyword embedder): tokens per session against full-history injection at 1 / 10 / 30 / 100 sessions, a fact planted 96 sessions ago retrieved 83 % of the time, superseded facts returned 0 % (its numbers, not re-measured). rtok's `memory` has no recall-quality number at all — `graph` has one (T8.8, 30 hand-labelled symbols) — and Gate P6 ("revert if recall is worse") has nothing to compare against. D3.
Done when:
1. `tests/memory_bench.rs` (`cargo test --test memory_bench -- --nocapture`, the `mode_bench` shape) builds an in-memory store from a seeded generator: N sessions (1, 10, 30, 100) × K notes of realistic length, 20 target facts planted at known session offsets, 5 of them revised later (T69.1); no network, no LLM.
2. Reported per configuration — FTS5 default; `half_life_days = 30` (T69.2); `embed.enabled` hybrid (P29): hit rate of the target in `mem_search` top-`search_limit` for a query built from the fact's own words; superseded facts returned (the test asserts 0 after T69.1); SessionStart recall bytes per session against the "full injection" baseline (every live body of the project) — rtok's own version of graymatter's table.
3. Numbers land in `research.md` §14 with the command and date and on the memory docs page; `README.md` / `docs/comparison.md` cite that row and never graymatter's. The gate for T69.2's default is written from this run.
4. The generator and the expected hit rates are checked in; a change that lowers the hit rate on any row fails the test.

**Result (2026-09-18).** Commits `b029b12`, `9af0c34`, `0cd5c27`. `tests/memory_bench.rs` seeded generator (N=1/10/30/100 × 6 filler notes, 20 planted facts, 5 `mem_revise`). FTS5 and P29 hybrid both 20/20; superseded returned 0. SessionStart recall 95–100 bytes vs 6 331 / 39 566 / 113 240 / 371 866 bytes full live-body injection. `half_life_days = 30` is N/A: T69.2 shipped no scorer. T69.2's default stays off (FTS5 already 20/20 at N=100). Numbers in `research.md` §14; cited from the memory page, `README.md`, `docs/comparison.md`. Never graymatter's 83 %.

**Check:** `cargo test --test memory_bench -- --nocapture` pass (1/1)

---
