---
status: in progress
priority: P2
complexity: 3
readiness: 85%
agent: Claude Code / sonnet-5.5
---

# T428. SessionStart hook back under the 10 ms budget

Renumbered from T418 on 2026-10-06: T418 is the finished `rtok worktree gc` task in `done.md`.

`rtok hook session-start` on the rtok repo took 12.7, 34.2, 13.4 and 65.0 ms on four manual runs (rtok 0.15.1, 2026-10-05) and warned `hook SessionStart slow … over max_ms 10 ms`. The output stayed correct and the exit code 0, but the fail-open rule asks for ≤ 10 ms.

Done: per-plugin timing of SessionStart on a real store shows where the time goes (memory recall, checkpoint restore, agent id, or store open); the slow part is fixed or moved off the hook path; `tests/latency.rs` covers SessionStart with a populated notes store and stays under its p95 bound.

Check: `tests/latency.rs` SessionStart case green; five manual `rtok hook session-start` runs on the rtok repo print no `slow` warning; `just check`.

Execution plan:

1. Time each phase of the hook (store open, `register_agent`, `record_call`, project upsert, each plugin's `session_start`, `inject::apply`, tail) on a copy of the real store (485 MB, 525 notes), idle and with six hooks racing; temporary trace, not committed.
2. Fix what the numbers show: SessionStart does about eleven write commits (against six for PreToolUse) and `memory::recall` reads every note body twice. Queue the three SessionStart measurements and write them in one transaction (`Runtime::defer_measurements` / `flush_measurements`, `Store::insert_measurements_once`); read each body once in `memory/mod.rs`.
3. `tests/latency.rs`: a SessionStart case with 40 notes of ~4k tokens and a `session:*` note.
4. Verify: A/B of the traced binaries, the latency test in release, five manual runs, `just check`.

Status: steps 1-3 done. On the rtok repo SessionStart dispatch is 2-4 ms on an idle host; the `slow` warnings come from write-lock waits and host load, so the fix cuts commits and reads. Left: re-run the `tests/latency.rs` release gate on a quiet host (it fails for every event at load average 35-60 because the spawn floor is already about 9-10 ms) and the five manual runs.


Execution plan (2026-10-12): 1. a neutral-name crate in `packages/crates` (own worktree and PR there, its AGENTS.md rules) holding `OPERATION_ICONS`, `icon()`, `ICON_WIDTH` and the gutter padding, generic over the tone/kind type, with unit tests; 2. after release-plz publishes it, rtok's `src/ui/style.rs` and ketch's `src/ui.rs` depend on the crates.io version with no output change; until then this card stays in progress with the crate PR linked.

Progress (2026-10-12): step 1 is done: crate `operation-icons` merged in the crates repository (PR #56) and not yet published. Step 2 waits for the creator to release it (the release pipeline has Actions off there); then rtok and ketch switch to the crates.io version.
