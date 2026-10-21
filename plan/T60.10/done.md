**T60.10 Sessions tab in the TUI is a static paragraph** · T60.3 · `src/tui/app.rs`, `src/tui/view.rs`, `tests/surface_parity.rs`
Do: Sessions gets the Calls row model — a `sessions` cursor (`↑/↓`, clamped to the visible rows), the selected row bold, `l` toggling the live-only filter, a derived scroll offset keeping the cursor row on screen, and a status line naming the keys. The `placeholder()` fallback is deleted; an unknown page is now a compile-time match error plus a `surface_parity` failure (`every_model_page_has_a_tui_body`).
Check: `sessions_page_claims_its_keys_and_clamps_the_selection` (app), `sessions_tab_lists_rows_and_l_filters_live_only` + `sessions_tab_scrolls_to_keep_the_cursor_visible` (TestBackend, 200-row snapshot), `every_model_page_has_a_tui_body` (surface_parity).
Complexity: 2/5 — one state struct, one page renderer, one parity test.
Status: done 2026-09-18
Check result: tui 31/31 green, surface_parity 5/5 green in the isolation worktree; fmt/clippy clean. Note: the surface_parity test rode into history inside the T69.1 commit (another agent committed the file while this card was in flight); the rest of this task is these two commits.
Model: ZCode / GLM-5.3-Flash
