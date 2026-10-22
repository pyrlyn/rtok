**T24.3 `rtok logs watch`** · T24.2 · `src/log.rs`, `src/cli.rs`
Do: print the same last-`lines` screen, then follow: every new line appears above the previous one,
so newest-first holds while it runs. Rotation while watching is handled — the file the watcher
holds is renamed, and it reopens `path` rather than following the inode into `.1`. Ctrl-C leaves
the terminal as it found it.
Check: a line written by another process shows up within a poll interval; a rotation mid-watch does
not end the stream and does not repeat lines already printed.
Complexity: 3/5
Status: done 2026-09-09 · Model: GLM-5.3 (subagent, effort High)
Check result: green. `watch_loop` polls a step closure every `WATCH_POLL` (200 ms, a pub const a
test reads) and renders each `WatchTick` two ways: on a TTY the newest-first screen is repainted in
place with `\x1b[{n}F` + `\x1b[J` so new lines land on top; piped, it appends only fresh rows
(numbered past the initial screen) with zero escape codes, and `BrokenPipe` maps to `Ok` so
`| head` exits cleanly. Rotation is detected by content (first line + line count — std has no
portable inode read), carrying the unconsumed tail out of the `.1`/`.2` chain before the
newcomer's lines; a rotation-storm unit test (12 appends across ~4 rotations at `max_bytes=150`)
asserts every line once in arrival order. Ctrl-C needs no handler: the loop only writes
row-move/clear escapes — no raw mode, no alternate screen — leaving the terminal as `tail -f`
would. Evidence: the integration test seeds the log, runs `logs watch` piped, appends from the test
process, rotates exactly as the sink does, appends again — both markers arrive, each exactly once,
no `\x1b` anywhere; the TTY repaint is unit-pinned byte-exact; a real-pty smoke run confirms the
row-1 landing. `watch_loop`/`WatchTick` is the skeleton T25.3 reuses for state-table repaint.
`cargo test --lib log::` 18 passed; `cargo test --test logs` 4 passed; `just check` exit 0
(workspace 284 passed, jscpd 37 clones / 1.42 %).
Deviations: content-based rotation detection instead of the inode the Do implies (std limitation;
the degenerate case degrades to skipping lines, never repeating). `tail` refactored into
`tail_with(live, …)` so the first screen and the follow state share one read — two reads repeat or
lose a line written between them; output unchanged, its tests untouched. Documented in code: a row
wider than the terminal makes the repaint drift one row (fixing it needs an ioctl or a dependency).
No new dependency (`std::io::IsTerminal`).
