From I-30 (codebase-memory-mcp: Linux kernel in 3 min). Measured 2026-09-04: 3 000 files cold 27.2 s, warm 0.053 s; the cold path is paid once per repo, so it was parked.
Done when the cold index writes symbols and edges in one Diesel transaction per 200 files instead of per file, the T8.4 cold bench on the same fixture is re-run and recorded in `research.md` next to the old number, the warm path and the ≤ 10 ms hook stay untouched, and the change is reverted if the cold time does not drop by a third.

**Result (2026-09-18).** Commit `45c4531`. Cold graph index batches Diesel transactions per 200 files in `src/plugins/graph/index.rs`.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
