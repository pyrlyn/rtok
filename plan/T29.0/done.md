**T29.0 design/survey: embeddings beside FTS5** · — · `src/plugins/memory/PLAN.md` and/or `src/plugins/graph/PLAN.md`
Do: survey mem0, code-review-graph embeddings, and at least one other embed path. Decide where vectors live, how they stay optional, and how a fixture is proven found by both FTS5 and embed. No implementation.
Check: PLAN (`src/plugins/memory/PLAN.md` v0.2 survey): ≥ 3 alternatives (mem0, code-review-graph, sqlite-vec chosen); vectors in `rtok.db`; Gate fixture `p29-gate-arctic-tern`.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
