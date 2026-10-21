---
status: todo
priority: P2
complexity: 3
readiness: 0%
agent:
---

# T934. Store "database is locked": hooks skipped, worktree claims not stored or released

Still seen after the purge transaction and `busy_timeout` fixes: 33 `hook <Event> skipped:
store locked` lines (5 on 2026-10-19), MCP `expand` failed with `database is locked`
(2026-10-14), `rtok worktree remove` printed `warning: claim not released: database is locked`
(2026-10-13) and, while filing this task, `rtok worktree add` printed `warning: claim not
stored: database is locked` (2026-10-20). A lost claim lets another agent take the same task.

Done when: claim writes retry or fail the command instead of warning, and the writer that holds
the lock that long is named (the store is 1.1 GB with a 56 MB WAL).

Check: a store test with a held write lock where `worktree add` either stores the claim or
exits non-zero; `just check`.
