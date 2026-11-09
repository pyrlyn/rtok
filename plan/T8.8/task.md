---
status: todo
priority: P1
complexity: 5
readiness: 0%
agent:
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

Needs the creator's OK before it lands: the PR in `pyrlyn/packages`, a new tag there, and
the bumped `ref` of that repository in rtok's CI.

Done means: the package builds and tests on its own; rtok's task code holds no board format
or concurrency logic of its own; the concurrency kit in `testing` shows that N clients racing
on one board (threads and processes) never lose a write, never claim a task twice, never
leave a half-applied batch after a crash, and never create a duplicate on a retried key.

Check: the package's own gate (`./gradlew check` there) green; in rtok
`./gradlew macosArm64Test cliTest e2eTest` green.
