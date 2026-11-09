---
status: todo
priority: P2
complexity: 4
readiness: 0%
agent:
---

# T8.5. Linear remote for two-way sync

`rtok task remote set linear --team KEY` connects the board to Linear through the T8.3
engine. A full Linear adapter (the package's `linear` module; GraphQL; list, get, create,
update, workflow state by type, native priority and parent) replaces the one-way
`LinearExporter.kt`; `export linear` becomes a one-way push alias. Spec: the
Linear column of the mapping table in [docs/design/task-board.md](../../docs/design/task-board.md).

Done means: the engine's sync tests pass against a fake Linear API, both ways, and the
exporter code is gone.

Check: `./gradlew macosArm64Test cliTest e2eTest` green.
