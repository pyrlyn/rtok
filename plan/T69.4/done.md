From the graymatter gap review (`research.md` §14). graymatter's `status` and its 4-tab `tui` show facts stored, memory cost (KB), recall counts, health and weight distribution. rtok's Memory dashboard page (`web::model::config_fields`) shows two config keys and nothing from the store: how many notes exist, per project and kind, their bytes, pinned / retired, recalls in the window and the bytes they injected. T69.2 step 1 needs these numbers, and D27 says anything the store keeps is a page.
Done when:
1. SessionStart recall records `Measurement { plugin = "memory", kind = "recall" }` (before = bytes of the live bodies the injected titles stand for, after = injected bytes, `ref_id` = session); `mem_search` / `mem_get` counts come from the `calls` rows `rtok mcp` already writes (`mcp::record`).
2. `rtok memory status [--project <name>] [--since 30d] [--json]` prints: notes live / pinned / retired, per project, per kind, body bytes, oldest / newest ts, recalls in the window with injected vs stood-for bytes, `mem_search` / `mem_get` calls; `--json` is the same `web::model` type (T60.1 rule: one serde path).
3. The Memory page on `web` and `tui` renders those rows through one model accessor (D23; `tests/surface_parity.rs`); a TUI `TestBackend` snapshot and a `tests/web.rs` case on an in-memory store with three notes; a trycmd golden for `status` on the fixture store (T60.2 style).
4. Docs: memory page and the CLI table in `README.md`.

**Result (2026-09-18).** Commit `45c4531`. `rtok memory status` CLI with `--json`; `src/plugins/memory/status.rs`; Memory page rows on web/tui; `tests/memory_status.rs` and trycmd golden.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)
