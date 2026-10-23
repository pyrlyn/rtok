---
status: todo
priority: P2
complexity: 4
readiness: 0%
agent:
---

# T970. `rtok worktree add` creates all or nothing and checks free space first

On 2026-10-22 21:36:22, `git worktree add` failed with `No space left on device` on the disk
that also broke CI. It left a half-made worktree, and three later adds failed with `already
exists`.

Done when: before `git worktree add`, `rtok worktree add` (CLI and MCP `worktree_add`) checks
the free space on the target volume and refuses with a one-line hint when the space is below a
configurable floor. On any failure after that point, it removes the directory, the branch it
created and the claim record. A branch or directory that existed before the call stays.

Check: tests that inject a failed `git worktree add` and a full-volume reading, then assert
nothing is left behind; `just check`.
