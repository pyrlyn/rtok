Confirmed with actual [lock_claim](src/tasks/disk.rs#L219) and [DiskGuard::drop](src/tasks/disk.rs#L64): A acquires `.claim.lock`; after the real stale interval, B takes over while A's guard still exists. Dropping A unconditionally unlinks the shared pathname, deleting B's fresh lock. C then acquires immediately while B still holds its guard, bypassing the normal rejection of fresh-lock contenders. A slow or paused holder can trigger this; no process crash is required. [Project::claim](src/tasks/run.rs#L224) relies on that guard to protect read/check/save, so the broken exclusion can permit conflicting task writes.

T442 explicitly chose ten-second stale takeover; that timeout policy alone is not filed again. The uncovered defect is the previous owner's release removing its successor's lock. The [existing stale/fresh test](src/tasks/disk.rs#L517) ages an ownerless file and never keeps the displaced guard alive. T541 concerns filename/frontmatter mismatches, not lock ownership.

Execution plan:

1. Add a deterministic A/B/C regression around takeover and delayed release. Assert A's release cannot remove B's lock and C cannot enter B's protected section. Keep fresh-contention and release/reacquisition controls.
2. Use an ownership-safe cross-process lock, preferably the existing platform file-lock abstraction, with a stable lock file and guard-owned handle. If leases remain necessary, add ownership and write fencing; a pathname existence check followed by unlink alone still races with replacement.
3. Define recovery for paused and crashed holders so an expired writer cannot resume protected writes concurrently with its successor. Preserve bounded wait/error behavior and coordinate claim, release, status and other disk mutations through the same mechanism.
4. Add isolated multi-process claim/save tests with delayed and terminated owners. Verify only one claimant wins and later guard release cannot expose another writer's critical section, then run task/disk tests and `just check`.

Check: releasing an older guard never removes a newer holder's exclusion, and protected task writes remain serialized. The scratch executable compiled actual guard/acquisition/staleness functions and used real temporary lock files and an 11-second wait; it held A/B/C guards in one process to reproduce the exact interleaving. Full task writes, multiple OS processes and remote adapters were not exercised.

Result: `lock_claim` holds an OS lock on a stable `.claim.lock` through `rtok_sys::try_lock_exclusive`; the lock lives in the guard's `File` and the file is never unlinked. `LOCK_STALE` and `lock_is_stale` are gone: a paused holder keeps others out, a killed one is released by the OS.

Check: `an_old_lock_file_does_not_let_a_second_claimant_in`, `a_killed_holder_releases_the_lock` (child process killed), `a_leftover_lock_file_is_taken_and_a_held_one_is_kept`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
