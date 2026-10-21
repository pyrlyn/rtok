**T15.7 Logs tab** · T15.0 · `src/tui/view.rs`, `src/web/model.rs`, `tests/surface_parity.rs`
Do (roadmap §TUI): Logs tab — render the model's log lines off the snapshot, newest first,
bound by `[log] lines` (the same selection `rtok logs` screens).
Complexity: 2/5
Status: done 2026-09-10 · Model: GLM-5.3 (subagent; policy tier GLM-5.3-Flash, Low) — cherry-picked from agent/T15.7 onto main after T15.6/T15.9
Check result: green. Snapshot gains `logs: Vec<String>` via `Model::log_lines(None)`;
`pages()` gains `("logs","logs")`; `logs` moves from surface_parity EXEMPT to COMMAND_PAGES.
Plain lines (no CLI ANSI numbering/colour). Tests: `logs_tab_renders_the_model_lines_newest_first`,
`logs_tab_honors_the_log_lines_bound`. Conflicts with T15.6 resolved by keeping both doctor and
logs pages. Originating worktree `just check` green; focused lib tests green after cherry-pick.
Deviations: none to the spec.
