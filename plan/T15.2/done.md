**T15.2 header · tabs · footer shell** · T15.1 · `src/tui/view.rs`, `src/tui/app.rs`
Do (roadmap §tui): the TUI shell — header line, tab bar, footer.
Complexity: 2/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent; policy tier GLM-5.3-Flash, effort Low)
Check result: green. Header `rtok · <project dir> · <cols>x<rows>`, tab bar with the selected tab
bold, body, footer `q quit · Left/Right or 1..N switch tabs · updated HH:MM:SS UTC` (reusing
`log::stamp` — one calendar implementation). Tabs render through ratatui's `Tabs` widget from
`tab_names()`; `selected()` (this task) drives the highlight. Shell rendering pinned by a
`TestBackend` test; `tabs_are_the_model_pages` still holds. `just check` exit 0.
Deviations: none.
