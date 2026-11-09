---
status: todo
priority: P1
complexity: 2
readiness: 0%
agent:
---

# T8.7. Task skills and docs for the board and remotes

The `rtok-tasks` skill ships from this repo instead of the Rust release, and teaches the
board: `skills/rtok-tasks/SKILL.md` (model, the eight MCP tools with their CLI twins, rules,
how to work in parallel), `creating.md` (how to write a task) and `remote.md` (connecting a
tracker, sync, migrate: only on the user's request). The release packages `skills/`.
`docs/tasks.md` documents the board, the remotes, the concurrency rules and the JSON-RPC API;
the `kotlin-taskboard` README documents the library; README, PORTING and `plan/README.md` note
that `rtok task` no longer matches the Rust binary's format. Spec: "Skills and agent
instructions" in [docs/design/task-board.md](../../docs/design/task-board.md).

Done means: `rtok agents` installs the new skill from this repo's release, the skill
matches `rtok task --help` and `rtok task prime`, and the docs cover every verb and tool.

Check: `./gradlew macosArm64Test cliTest e2eTest` green; the doctor skill check passes; a
fresh agent session that only reads the skill can create, claim and finish a task.
