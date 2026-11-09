---
status: in progress
priority: P1
complexity: 5
readiness: 95%
agent: Claude Code / claude-opus-5-5
---

# T8.8. Task board library with a concurrency API

The creator wants the board as a library with an API for parallel work without conflicts.
This task creates `packages/kotlin-taskboard` (a new project in `pyrlyn/packages`: Kotlin
Multiplatform, rtok's native targets, its own `AGENTS.md`, `README.md`, `plan/`,
`roadmap.md`, `ideas.md`, `toolchain.md`) with the `core`, `files` and `testing` modules:
the model, the `Store` SPI, the `FileStore` that T8.1 built inside rtok (moved, not copied),
and the engine behind `Board`: revisions with `if_rev`, field merge with `base_rev`, atomic
claim-next, all-or-nothing batches with a redo log, idempotency keys, leases with a TTL and
a liveness hook, the journal with `changes` and `watch`, hand-edit detection, board ids from
`.state/ids.json`. rtok then depends on it: `TaskProject` opens a `Board`, the legacy
`files` and `plan` layouts become `Store`s, `task_claims` gives way to leases, and the
existing commands keep their output. Spec: "Library" and "Concurrency" in
[docs/design/task-board.md](../../docs/design/task-board.md).

The creator approved the steps outside this repo: the PR in `pyrlyn/packages`, a new tag
there, and the bumped `ref` of that repository in rtok's CI.

Done means: the package builds and tests on its own; rtok's task code holds no board format
or concurrency logic of its own; the concurrency kit in `testing` shows that N clients racing
on one board (threads and processes) never lose a write, never claim a task twice, never
leave a half-applied batch after a crash, and never create a duplicate on a retried key.

Check: the package's own gate (`./gradlew check` there) green; in rtok
`./gradlew macosArm64Test cliTest e2eTest` green.

## Execution plan

1. In a worktree of `pyrlyn/packages`, scaffold `kotlin-taskboard` like `kotlin-snapshot`:
   `shared-settings` native convention (rtok's targets), modules `core` and `testing`, the
   project files, its own `plan/T1`, and a row in the repository README.
2. `core`: the model (`Task`, `Status`, `TaskId`, `Rev`, `NewTask`, `Patch`, `Op`, `Write`,
   `Versioned`, `BoardView`, `Change`, `Lease`, `BoardError` with stable codes), the JSON codec
   (rtok's task JSON plus `rev`, `lease`, `report`), ready and next rules ported from rtok,
   the `Store` / `Session` SPI (unlocked snapshot; a locked session that reads tasks and state
   and commits task and state changes all or nothing), and the engine behind `Board`.
3. `testing`: an in-memory store, a fake clock, and a race kit (N clients on one board).
4. After T8.1 merges: `files`, holding T8.1's board format moved out of rtok as `FileStore`
   (flock on `.lock`, `.state/` files, the `txn.json` redo log, hand-edit detection), with race
   tests that open separate stores on one directory from several threads (flock blocks
   between separate open files, as between processes).
5. Package gate green, PR in `pyrlyn/packages`, merge, tag `0.0.2`.
6. rtok: include the build with `sharedBuild`, bump the CI `ref` to `0.0.2`, open a `Board` in
   `TaskProject`, adapt the legacy `files` and `plan` layouts as stores with state under
   `.rtok/taskboard/`, replace `task_claims` with leases, delete rtok's own board code, keep
   every command's output; gates green; rtok PR.
