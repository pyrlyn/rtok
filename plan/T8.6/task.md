---
status: todo
priority: P2
complexity: 4
readiness: 0%
agent:
---

# T8.6. Jira remote for two-way sync

`rtok task remote set jira --site URL --project KEY` connects the board to Jira Cloud (email
plus API token) or Data Center (personal access token) through the T8.3 engine: search by
the `rtok` label, create, update, status by transition to the column's status category,
won't-do as a resolution when the project has one, native priority, parent when the issue
type allows it. Spec: the Jira column of the mapping table in
[docs/design/task-board.md](../../docs/design/task-board.md).

Done means: the engine's sync tests pass against a fake Jira API, both ways.

Check: `./gradlew macosArm64Test cliTest e2eTest` green.
