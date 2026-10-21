**T25.3 `rtok agent sessions watch`** · T25.2 · `src/cli.rs`, `src/render.rs`
Do: the same table, redrawn on an interval, in place rather than scrolling; a session that appears,
ends or spends tokens shows up without a restart. Not a TUI — one screen, no key handling, and it
leaves the terminal as it found it on Ctrl-C. Where `rtok logs watch` (T24.3) streams new lines,
this one repaints state; both share the poll-and-print loop rather than growing two.
Check: a session started while `watch` runs appears within one interval and its duration advances;
piping the command produces plain repeated tables, not escape codes.
Complexity: 2/5
Status: done 2026-09-09 · Model: Muse Spark 1.3 (subagent-sessions-watch)
Check result: green in a detached worktree at HEAD holding only this task's files
(`src/cli.rs`, `src/render.rs`, `tests/agents.rs`, `tests/surface_parity.rs`) with an isolated
`CARGO_TARGET_DIR` — the shared tree is mid-flight with parallel uncommitted work (T15.6's
Doctor page, T22.2's HTML renderer) that does not compile here, so the main tree cannot go
green until those land. `cargo test --lib` 209 passed including the new
`render::a_sessions_tick_repaints_state_and_stays_quiet_otherwise` (new session / spent tokens /
duration tick repaint, unchanged poll stays quiet, no `\x1b` in any row); `cargo test --test
agents` 4/4 including the new `watch_shows_a_session_started_mid_run_and_repeats_plain_tables`
(seed live + ended, spawn `agent sessions watch` piped, insert `watch-new` from this process,
it appears, the ended `watch-gone` never shows, a further header lands with no writes at all
proving the duration repaint, output has no escape codes, ≥3 headers proving repeated tables);
`surface_parity` 2/2, `config_coverage` 1/1 (no new flags — `watch` is a subcommand, `all` was
already allowed); `clippy --lib --tests -D warnings` clean; `cargo fmt --check` clean; `just
dup` green (1.35%, threshold 2). TTY repaint is `watch_loop`'s own (T24.3): cursor-up + clear,
no raw mode / alternate screen, so Ctrl-C leaves the terminal as found; BrokenPipe maps to Ok
so `| head` exits cleanly.
Deviations: four files, not two — `tests/agents.rs` carries the Check's e2e and
`tests/surface_parity.rs` gains the `agent sessions watch` streaming exempt without which
T15.12 fails by name; product code is ~110 lines, the +199 is the e2e. `Sessions::all` became
`global = true` (the shape `Logs::lines` already uses) so `sessions watch --all` and `sessions
--all watch` both parse; no config key, no behaviour change to the plain table. No new
dependency, no second loop: the watch arm calls `crate::log::watch_loop` with
`crate::log::WATCH_POLL` and `render::sessions_tick`; a transient unreadable store keeps the
previous screen instead of blanking. `WatchTick` has no `Debug`, so the unit test asserts on
`fresh`/`screen` contents rather than `{tick:?}` — `src/log.rs` stays untouched per the file
list.
