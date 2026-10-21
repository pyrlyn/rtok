**T60.8 TUI help overlay and manual refresh** · - · `src/tui/app.rs`, `src/tui/view.rs`
Do: the `KEYS` table in `app.rs` is the one source every hint renders from — the `?` overlay (globals plus the current page's rows), the footer, the Plugins and Sessions status lines are all generated from it; `?` toggles the overlay and `r` re-reads the model before the next tick, both global.
Check: `help_overlay_lists_the_keys_and_toggles` and `r_refreshes_the_snapshot_immediately` (TestBackend), `question_mark_and_r_are_global` and `keys_table_covers_the_row_state_pages` (app), the footer test updated to the generated hints.
Complexity: 1/5 — one table, two keys, one overlay renderer.
Status: done 2026-09-18
Check result: tui 35/35 green, fmt/clippy clean, full `just check` green in the isolation worktree. Deviation: the key handler stays behavioural code — the table is the single rendered source, not the dispatch mechanism; the generated footer replaced the hand-written hint list, and three tests that pinned the old wording were updated in the same commit.
Model: ZCode / GLM-5.3-Flash
