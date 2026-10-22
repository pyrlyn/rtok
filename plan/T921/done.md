# T921. Done

No hook call waits on a host that holds stdin open or on a hung `rtok hook` any more: every path
answers `{}` with exit 0 after `rtok_hook::DEADLINE` (3 s, under the host's 5 s).

- `rtok hook` arms a watchdog thread first thing (`hooks::deadline`). If the call is still running
  at the deadline it claims the output gate, prints `{}`, writes one stderr line and exits 0; the
  hook claims the same gate before it prints, so stdout never gets two answers. This covers the
  `hooks.json` fallback that runs `rtok hook` directly when `rtok-hook` is not on PATH.
- `rtok-hook` reads stdin on a thread bounded by the deadline, feeds a fallback child's stdin on a
  thread (a child that never reads no longer blocks it on a full pipe) and polls the child, killing
  it at the deadline plus 500 ms (`CHILD_GRACE`) and printing `{}`. Before, `child.wait()` and
  `read_to_end` had no limit.
- `RTOK_HOOK_DEADLINE_MS` overrides the deadline (tests hang a hook for 300 ms instead of 3 s).

## The cause of the hangs

Inferred from the store, not reproduced. `calls` holds 491,476 hook rows since 2026-09-14 and none
reached 5 s (max 4.6 s): the 425 cancelled calls never wrote a row, so they stalled before or
outside the dispatcher. Of the rows that did finish, 128 took over 1 s, on PreToolUse (156 over
500 ms), SubagentStart (144) and PostToolUse (24) alike, in bursts (2026-10-13: 29, 10-14: 14,
10-09: 13). A store lock cannot be the cause: the hook waits 5 ms on it (`LOCK_WAIT`) and logs
`skipped: store locked`. Machine load is the match: the parallel agent sessions run cargo builds and
the load average read 40 to 52 while this task ran, and a freshly started `rtok hook` took 13 s
to answer in one of the tests. A process that is starved before its own `main` runs cannot be
helped by anything inside it; the watchdog bounds every case where the process runs but waits.

## SessionEnd

The median already meets `max_ms`: 61% of the 1,354 live runs are under 10 ms. The tail (p95 93 ms,
max 896 ms) is `checkpoint::save_session`, which reads the whole transcript on the hook path; a
long session pays for it. `latency_hook_session_end_median_under_10ms` now covers the median with a
small transcript (release build, like the other latency cases). Moving the checkpoint off the hook
path is a separate task.

## Check

- `tests/hook_deadline.rs`: `rtok hook` with a host that never closes stdin, and `rtok-hook` whose
  `rtok hook` fallback is a script that sleeps, both answer `{}` with exit 0; a normal call under
  a long deadline is not cut short. Before this change the first two never returned. The wall
  bound is 20 s because a process start measured 13 s on a loaded machine: it tells an answer
  from a hang, not a latency.
- `hooks::deadline` unit tests (fires, disarms, one winner of the gate) and the `deadline()`
  parse in `rtok-hook`.
- `just check`.
