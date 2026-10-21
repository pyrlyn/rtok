---
status: in progress
priority: P2
complexity: 3
readiness: 60%
agent: Claude Code / claude-sonnet-5-5
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

Plan:
1. `rtok_store::retry_locked`: rerun an idempotent store write while it fails on "database is locked".
2. `worktree::claim::remember` / `release` use it (8 tries, about 10 s) and return the error, so `worktree add`, `adopt` and `remove` exit non-zero instead of warning; the `WorktreeRemove` hook keeps its warning.
3. Tests: a held `BEGIN IMMEDIATE` that ends inside the retries (claim stored), one that outlasts them (error), and the CLI `worktree add` under a held lock.
4. Name the long writer in `done.md` from the code and the store's table sizes.
