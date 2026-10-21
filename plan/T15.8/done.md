**T15.8 CLI + `[tui]` config** · `roadmap.md` §TUI · `src/cli.rs`, `src/config/{mod,layers,validate}.rs`, `src/tui/{mod.rs,app.rs}`, `config/default.toml`, `docs/config.md`
Do (roadmap §TUI): `rtok tui` CLI flags and the `[tui]` config section, by the `[web]`
precedent (`web_flags` + host/port keys, T21.3 migration shape): every flag has a key (D12),
clap derive (D14).
Check: `rtok tui --help` shows the flags; `config show --sources` resolves every `[tui]` key
with origin; the T12.4 coverage test is green.
Complexity: 2/5
Status: done 2026-09-10 · Model: (subagent-tui-config)
Check result: green. `rtok tui --tab <page> --tick-secs <n>` over `[tui] tab` (`""` = first
tab) and `tick_secs` (= 2, the web socket's tick in `src/web/mod.rs`). An empty or unknown tab
falls back to the first page rather than failing the surface (fail open, D1); the loop clamps
the cadence to ≥ 1 (`--tick-secs 0` cannot busy-poll) and `config validate` rejects
`tick_secs = 0` in the file. `config show --sources` resolves both keys through every layer
(`RTOK_TUI_TAB` / `RTOK_TUI_TICK_SECS` via the leaf table; flags via `layers::tui_flags`).
New tests `tui_tab_picks_the_opening_tab` and `tui_flags_beat_env`. Verified in a detached
worktree at `b7d4c2e` plus only these hunks: `cargo fmt --check`, `clippy --all-targets`
(no warnings), full `cargo test` green (incl. `config_coverage`, 9 `tui::`, 20 `config::`).
The shared tree's lib-test could not link at the time (T22.5's mid-flight `src/report/ai.rs`
vs `src/web/model.rs`), which is why the worktree carried the verdict; none of its files are
in this commit.
Deviations: the file set is wider than the brief's three — `layers.rs` (the `tui_flags`
overlay, where `web_flags` lives), `config/mod.rs` (the `Tui` section, where `Web` lives),
`validate.rs` (one range arm), `src/tui/` (the wiring the flags actually drive) — each required
by the `[web]`/T22.4 precedent the brief names. No T21.3 migration arm: no legacy `[tui]` key
exists. T15.9 (TTY guard) stays open.
