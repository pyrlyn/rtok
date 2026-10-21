From `research.md` §11. `rules::dedupe` folds consecutive identical lines to `line (×N)`; logs repeat the same line with a different timestamp, pid or request id and never fold, and a line that repeats after one other line never folds either.
Done when `dedupe = "normalized"` (the current behaviour stays `dedupe = true`) keys a line with timestamps, hex ids, pids and durations replaced by placeholders, folds every later match into the first occurrence as `line (×N, also lines k, l, …)` keeping the first verbatim, and a fixture of 3,000 log lines (nginx access log, a `cargo test` run with 200 identical warnings, `kubectl logs`) shows the bytes saved against `dedupe = true`; unit tests for the key normaliser (no false merge of two different error codes). Ordering of the kept lines is unchanged so the head/tail cut still works.

**Result (2026-09-18).** Commit `45c4531`. `dedupe = "normalized"` mode with timestamp/pid/id placeholders; non-adjacent duplicate folding with line references.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
