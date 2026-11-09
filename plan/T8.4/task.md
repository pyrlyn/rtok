---
status: todo
priority: P2
complexity: 3
readiness: 0%
agent:
---

# T8.4. GitLab remote for two-way sync

`rtok task remote set gitlab` connects the board to GitLab Issues through the T8.3 engine.
`GitlabAdapter` learns to write title and description, report strays, and return the issue's
`updated_at`; status uses the existing `status::*` labels (work item Status when the
namespace has it is a follow-up). Spec: the GitLab column of the mapping table in
[docs/design/task-board.md](../../docs/design/task-board.md).

Done means: the engine's sync tests pass against a fake GitLab, both ways.

Check: `./gradlew macosArm64Test cliTest e2eTest` green.
