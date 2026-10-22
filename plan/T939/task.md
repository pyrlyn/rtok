---
status: todo
priority: P3
complexity: 2
readiness: 0%
agent:
---

# T939. `errors.log`: deliberate refusals and broken pipes logged at error level

About 380 of 1,259 `errors.log` lines are refusals that worked as intended (`is locked by` 256,
`not merged into origin` 49, `one worktree per task` 31, `uncommitted` 10), and
`Broken pipe (os error 32)` is logged as an error 4 times. Real errors are hard to see.

Done when: refusals and a closed stdout log below error level, and `errors.log` holds only
unexpected failures.

Check: CLI tests that a refused `worktree remove` and a broken pipe write nothing to
`errors.log`; `just check`.
