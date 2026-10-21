From the codegraph / graphify review. graphify `path A B` and codegraph's "call paths between them" answer "how does A reach B"; rtok's `impact` walks outward from one symbol and prints every reachable definition, so the model reads the whole fan-out to find one chain.
Done when `impact` takes optional `to` (MCP field, CLI `--to`) and prints only the chains from `name` that reach `to` within `depth`, one line per chain `a → b → c` in BFS order, `no path from a to b within depth N` when none, the same `impact_bfs` walk with its parent map kept (no second traversal); the LSP backend applies the same filter on its `callHierarchy` result; description still ≤ 60 tokens; unit test on the `impact` fixture (one chain found, one absent, depth too small).

**Result (2026-09-18).** Commit `45c4531`. `impact` gains optional `to` / `--to`; prints only chains reaching the target within depth via `symbol_paths`.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
