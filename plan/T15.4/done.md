**T15.4 Plugins tab (toggle enabled)** · T15.0 · `src/tui/{app,view,mod}.rs`, `src/config/mod.rs`
Do (roadmap §TUI): Plugins tab — row cursor and toggle that writes `plugins.<id>.enabled`
through `config set`'s writer (`toml_edit` on `<home>/config.toml`), never a second config writer.
Complexity: 3/5
Status: done 2026-09-10 · Model: GLM-5.3 (subagent; High) — cherry-picked from agent/T15.4 onto main after T15.6/T15.7/T15.9
Check result: green. `Config::set_plugin_enabled` mirrors `plugin_enabled`; App owns the config
and the loop's timeout calls `app.tick()` so a toggle's re-read is not clobbered. Up/Down move
the cursor (page-scoped); Space/Enter flip the selected plugin; refusal is a status line (D1).
Tests: `plugins_cursor_moves_and_clamps`, `space_toggles_the_selected_plugin_through_config_set`,
view pins for rows/cursor/hint/status, `set_plugin_enabled_flips_every_catalogue_id`. Hermetic
doctor paths added on `cursor_on_plugin` after T15.6. Originating worktree `just check` green;
focused lib tests green after cherry-pick.
Deviations: 4 files, +325/−35 on the originating commit (over ≤3 files: config writer lives with
`plugin_enabled`). Cherry-pick conflict in `view.rs` resolved keeping Doctor/Logs match arms.
