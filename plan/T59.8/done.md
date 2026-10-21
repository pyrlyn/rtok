From I-48 (caveman `learn`, context-budget plugin). `stats` has per-family and per-tool rows and `report` renders the D24 rules; what is missing is "which ten paths and commands cost the most, and which rtok switch would have shortened each".
Done when `report` gains one rule that prints the top-10 sinks by bytes over the session window (file path for Read/read, first stem for Bash/cmd, server/tool for MCP), each with the switch that applies (`read.default_mode = map`, a `[stem]` rule, `--wrap`, or "none: already shortened"), sourced from `Measurement` rows only, with a fixture test and a line on the report docs page.

**Result (2026-09-18).** Commit `45c4531`. Top-10 token-sink rule in `src/report/advice.rs`; fixture test; `docs/report.md` updated.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
