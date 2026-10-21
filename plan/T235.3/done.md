Load-incident context in `done.md` → T235.1.

- An `apps/rtok/target/debug/rtok logs watch --lines 5` had been running for 5.5 days with ppid 1: `logs watch` does not exit when the terminal or agent that started it goes away.

Check: `rtok logs watch` exits when its parent dies or its stdout closes (SIGHUP/SIGPIPE, or ppid becoming 1), covered by a test.

Do (Claude Code / claude-opus-5-5, 2026-09-24): `log::watch_loop`, shared by `rtok logs watch` and every other watch command, now records its parent pid at start. It stops once the pid changes (reparented to a subreaper) or is 1 (reparented to init; this also covers a parent that exited before rtok read it). New `rtok_sys::parent_pid()` is rustix `getppid` on Unix and `None` on Windows, where a parent pid is never updated, so the check is off there. SIGHUP already ended an attached watch when its terminal closed, and a closed stdout ends it on the next failed write. The orphan in the card had neither: its stdout never errored, and it had nothing to print.

Check result: new `tests/logs.rs::watch_exits_once_its_parent_is_gone` backgrounds `rtok logs watch` (stdout `/dev/null`) from an `sh` that exits at once; the watch is gone within one poll. Before the ppid-1 rule it was still running after 10 s. `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
