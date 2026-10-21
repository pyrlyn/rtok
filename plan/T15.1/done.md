**T15.1 ratatui + crossterm scaffold, event loop** · T15.0 · `Cargo.toml`, `src/lib.rs`, `src/cli.rs`, `src/tui/{mod,app,view}.rs`
Do (roadmap §tui): the scaffold — dependencies, module, event loop, `rtok tui` subcommand.
Complexity: 2/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent; policy tier GLM-5.3-Flash, effort Low)
Check result: green. `ratatui 0.30.2` (the widget layer; its default backend is crossterm) and
`crossterm 0.29` (key/tick events; same major as ratatui 0.30's backend so exactly one copy
links) — reasons in the commit message. `src/tui/mod.rs` runs `ratatui::try_init`/`restore` on
every exit path; the loop draws, `event::poll(2 s)` (the web tick's cadence), re-reads
`model::snapshot(cfg)` on timeout, and routes keys through `App::key` (`q`/`Esc`/`Ctrl+C` quit,
Left/Right wrap, `1..=9` jump). `App` holds `tabs = model::pages()` **by reference** — no second
list anywhere; pinned by `tabs_are_the_model_pages`. The TUI never opens the `Store` (D23): data
only via the model's snapshot. Unit tests cover tab switching and the pages-derived tab list; a
real-pty smoke run rendered the header, bold tabs, usage body and footer without a panic (a 0×0
pty also does not crash — the real TTY guard is T15.9). `just check` exit 0 per commit.
Deviations: T15.1's placeholder view renders one line consuming app state rather than a bare
hello — private `mod app` accessors trip `-D dead_code` otherwise; the shell replaced it in T15.2.
