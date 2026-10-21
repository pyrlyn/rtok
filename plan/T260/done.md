Found 2026-09-23 in the D23 surface audit: the TUI Sessions tab filters live sessions with `l` (`src/tui/app.rs:32,133,417-419`) and `?` opens an overlay listing `KEYS` (`src/tui/view.rs:59`); the web `SessionList` in `crates/rtok-webui/ui/app.slint` has no live toggle and the web has no help at all. `ended_at` already rides the wire (`crates/rtok-webui/src/lib.rs:273-274`), so the filter is UI-only.

Plan: a "live only" `CheckBox` on the web sessions page bound to a `sessions_live_only` property filtered in `lib.rs` (same shape as the skills "never invoked only" box); a `?` help button/overlay listing the web actions (expand, filter, toggle, theme, live). No new snapshot fields.

Check: `tests/surface_parity.rs` gains `sessions_live_filter_exists_on_both_surfaces` (source scan like `skills_page_exists_on_both_surfaces`); `just check` green.

Result: a "live only" `CheckBox` on the web Sessions list (`sessions-live-only` on `MainWindow`, bound into both `SessionList` layouts; rows filter on the `live` flag `SessionRow` already carries) and a `?` button in the mobile bar and the sidebar opening a help overlay that lists expand, filter, toggle, theme and live. `tests/surface_parity.rs` `sessions_live_filter_exists_on_both_surfaces` (fails when either side loses its string); webui e2e `sessions_live_only_checkbox_hides_ended`. Filtering stays in Slint like the skills "never invoked only" box, so `lib.rs` is unchanged.
Status: done 2026-09-24
Check result: `just check` green (1754 passed, 3 skipped); `rtok-webui` tests 11 passed; `just webui-check` clean.
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
