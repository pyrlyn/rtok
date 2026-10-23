---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T971. A task file without front matter is skipped with a warning, not fatal to the list

`src/tasks/plan_dir.rs` (`split`) and `src/tasks/disk.rs` (`scan`) `bail!` on one malformed
file, so `rtok task list` and the MCP `task_*` tools fail for the whole project. That happened
in the 2026-10-22 logs.

Done when: listing and picking skip a file that does not parse and warn once with its path and
the reason. Commands that take that task's id still fail with the same error. Writes never
touch the skipped file.

Check: tests in `plan_dir.rs` and `disk.rs` with one broken file next to valid ones; `just check`.
