**T15.5 Calls tab (P13 rows + detail)** · T15.0 · `src/store/mod.rs`, `src/web/model.rs`, `src/tui/{app,view}.rs`
Do (roadmap §TUI): Calls tab — list P13 ledger rows (newest first) with Enter/z detail pane
for the selected row; the page rides the snapshot (D23/D27).
Complexity: 3/5
Status: done 2026-09-10 · Model: GLM-5.3 (subagent; High) — cherry-picked from agent/T15.5 onto main after T15.4/T15.6/T15.7/T15.9
Check result: green. `Store::recent_calls(limit)` joins host/provider/model slugs and newest
usage; `Model::calls` + Snapshot.`calls` + `pages()` `("calls","calls")`; bound `CALLS_ROWS=120`.
App `CallsState` + page-scoped Up/Down/Enter/z; view list + detail pane. Tests:
`recent_calls_is_newest_first_bounded_and_linked`, `calls_page_is_the_store_read_and_rides_the_snapshot`,
view list/detail/empty, `calls_page_claims_its_keys_and_clamps_the_selection`. Conflicts with
T15.4/T15.6/T15.7 resolved keeping all pages; `fresh_store` shared with Overview seeding.
All 24 `tui::` tests + surface_parity green after cherry-pick.
Deviations: 4 files, +648/−12 on the originating commit (over ≤200 LOC/≤3 files — store+model+app+view
by construction; T15.3/T25.1 precedent).
