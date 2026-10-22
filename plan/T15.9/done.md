**T15.9 TTY guard, `q` restores the terminal** · T15.1 · `src/tui/mod.rs`, `tests/tui_tty.rs`
Do (roadmap §TUI): refuse `rtok tui` without a TTY before `try_init` touches terminal state;
`q` / Esc / Ctrl-C restore via `run()`'s `restore()` on every loop exit (T15.1); ratatui's
`try_init` already installs the panic hook that restores.
Complexity: 2/5
Status: done 2026-09-10 · Model: GLM-5.3 (subagent; policy tier GLM-5.3-Flash, Low) — cherry-picked from agent/T15.9
Check result: green. Guard message names both stdin and stdout; exit 1 via anyhow Termination;
no escape codes on the refuse path. `tests/tui_tty.rs` runs the real binary with all stdio
piped (exit 1, one stderr line, clean stdout, no ESC); unit test covers all four tty-flag
combinations. `just check` green on the originating worktree; cherry-picked onto main after
T15.6.
Deviations: none. No second panic hook added.
