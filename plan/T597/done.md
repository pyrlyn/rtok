Confirmed in [stop](src/demon.rs#L208): a saved state file is enough to send TERM or KILL to its supervisor and child PIDs; the timestamp is never checked. A state left over from an earlier boot can therefore cause `demon stop`, `demon kill`, or the stop phase of `demon restart` to terminate unrelated processes after those positive PIDs are reused. [start](src/demon.rs#L154) and [quiesce](src/demon.rs#L263) already guard stale retirement with the boot time, but stop bypasses that protection. [process_term/process_kill](crates/rtok-sys/src/lib.rs#L48) validate positive PIDs, not process identity. T73 covers start/quiesce around upgrade; no active card covers this stop-path gap.

Execution plan:

1. Add deterministic tests with pre-boot state and reused positive PID identities for normal stop, forced kill and restart. Use a recording process adapter and assert no signal reaches unrelated processes; retain missing-state and valid-live-service controls.
2. Apply one shared stale-state/identity check before any initial or escalation signal. Reject pre-boot records and handle unknown boot information conservatively; do not let force mode bypass ownership checks. For current-boot PID reuse, verify a process birth token or equivalent platform identity rather than treating liveness alone as ownership.
3. Retire stale metadata with an explicit result while preserving any concurrently published new supervisor state. Keep stop-marker ordering and the valid-service shutdown/escalation behavior; revalidate identity before escalation to avoid signalling a newly reused PID.
4. Cover restart and upgrade callers, unavailable identity information, malformed state, nonpositive PIDs and concurrent service replacement using fakes. Rerun demon/platform tests and `just check`.

Check: stale state never authorizes a signal to a different process, including forced shutdown; valid owned services still stop and restart. The scratch probe compiled actual Service/State, file/read, targets and stop functions with a recording-only process adapter. State `since=1` sent TERM to both simulated reused PIDs in normal mode and KILL in force mode, then removed the state; the missing-state control sent nothing. No OS process was signalled, and real kernel PID reuse or full CLI integration was not exercised.

Result: `src/demon.rs` gets a `Procs` seam and an `ownership` check for `stop` (normal and `--force`), orphan retirement and `quiesce`: a held supervisor lock signals both pids, a same-boot state without a lock only the child, anything else signals nothing. Escalation re-checks the state file. Pid reuse within one boot under a held lock is not covered.

Check: `demon::` 15, `--test demon` 7.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
