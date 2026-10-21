From the codegraph / graphify review. codegraph prepends a banner naming files edited during the watcher's debounce window and tells the model to read those directly; graphify re-extracts on post-commit / post-checkout. rtok marks a file stale in `post_tool` and the watcher settles after `QUIET`, but a `symbol` call inside that window, or any call with `auto_index = false`, answers from old rows and says nothing.
Done when every graph tool answer with `auto_index = false`, or with the watcher on and a non-empty pending set, starts with `stale: N files pending (a.rs, b.rs, …)` (up to 5 names, sorted, byte-stable for the same pending set) followed by the answer; `Store::symbol_pending(root)` counts rows carrying the T8.3 stale mark; the watcher publishes its in-flight pending set through `Ctx` (one shared set behind a mutex, read-only from the tools); `rtok graph status` (CLI, `--json` per T60.1) prints root, rows, files, pending, watcher mode and last index time; a test edits a file with `auto_index = false`, sees the line, runs `graph index`, sees it gone. Hook p95 unchanged (the line is built on the MCP path only).

**Result (2026-09-18).** Commit `45c4531`. Staleness banner on graph answers; `rtok graph status` CLI; `src/plugins/graph/status.rs`; watcher pending set via `Ctx`.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
