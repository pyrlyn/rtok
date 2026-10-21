From the codegraph / graphify review. codegraph exposes `callees`; graphify's `explain` shows a node's outgoing edges. rtok stores the edge already (T8.5: every reference row carries `scope` = its enclosing definition) and shows only the incoming side (`callers`).
Done when `symbol` output ends each definition with one `calls: a, b, c (+N)` line — distinct referenced names whose `scope` is that definition, ordered by first line, capped at `body_lines / 2` names — from one `symbol_callees(root, name)` query on the existing rows (no schema change, no new tool: the surface budget is spent on T68.1); the LSP backend derives the same line from `documentSymbol` plus references inside the span or prints nothing; the unfiltered `symbol` contract strings are re-blessed once in the same commit; unit test on the `impact` fixture asserts the chain reads forward as `callers` reads it backward.

**Result (2026-09-18).** Commit `45c4531`. `symbol` prints `calls:` line per definition via `Store::symbol_callees`; contract strings re-blessed.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
