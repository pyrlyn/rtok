From I-54 (`research.md` §12). After T67.1 the model needs two calls to see the lines around a hit (grep, then `--lines`); in rtok's metric every extra call is a turn that re-reads the whole prompt, so one call that returns hit ± N lines is cheaper than two smaller ones.
Done when `expand` takes `context` (CLI `--context N`, MCP `expand.context`, default 0 = today's output) and, with `grep`, prints each hit with N numbered lines before and after it, overlapping windows merged, windows separated by `--`, still under `expand.max_lines`; without `grep` the flag is ignored; per call like `--grep` (no config key; the `config_coverage` allow-list and the `docs/config.md` row name it); a test with two hits whose windows overlap and one at the file edge; the README example gains the one-call form.

**Result (2026-09-18).** Commit `13a6608`. `expand --context N` merges overlapping windows around grep hits; MCP `expand.context` field; README example updated.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
