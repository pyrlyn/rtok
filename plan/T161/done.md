T143's test (`tests/otel.rs`) failed on `check (ubuntu-latest)` for PR #157, a diff that does not touch otel: `queued lock held: no flush process should have been spawned at all — left: 1, right: 0`. Cause: the flush child writes its `<pid>.run` trace marker only once it runs, so the warm-up drain loop cannot tell "not started yet" from "already exited". On a loaded runner the drain passes at once, the late warm-up child then starts while the test holds the queued slot, and its marker is counted by the final assertion. The same blind spot sits in the burst test's drain.

Plan: `spawn_child` writes `<child pid>.spawned` in the trace dir right after a successful spawn (test hook only, same env var); `FlushTrace` renames `.run` to `.done` on exit instead of deleting it. Tests count `.run` for "alive", wait until `.done` equals `.spawned` to drain, and assert the skip case by the `.spawned` count right after the hook exits — synchronous, so a spawn is caught even if the child never gets to run. Production behaviour unchanged.

Check: `mise exec -- cargo nextest run --test otel` passes repeatedly (and under CPU load); the assertion fails if `spawn_child` drops its `queued_lock_held` pre-check; `just check`.

Do (Claude Code / claude-opus-5): `spawn_child` in `src/otel/export.rs` writes `<child pid>.spawned` into `RTOK_OTEL_FLUSH_TRACE` after a successful spawn; `FlushTrace` renames `<pid>.run` to `<pid>.done` on exit. `tests/otel.rs`: `trace_files(dir, ext)` counts one kind of marker, `trace_drained` waits until nothing runs and every spawned child reported done; both T143 tests drain through it, and the skip assertion counts `.spawned` (exactly the warm-up's one) right after the hook exits, plus no `.run` left.

Status: done 2026-09-22
Check result: `cargo nextest run --test otel` 15 passed, 10 consecutive runs green; with a 1.5 s sleep injected before the child's marker the new test still passes while the old test fails; with `queued_lock_held` removed from `spawn_child` the new test fails with "queued lock held: no flush process should have been spawned at all" (left 2, right 1); `just check` green.
Model: Claude Code / claude-opus-5
