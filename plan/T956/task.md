---
status: todo
priority: P1
complexity: 2
readiness: 0%
agent:
---

# T956. `worktree gc` keeps a merged worktree whose locking agent has ended

Bug. `gc::decide` (`src/worktree/gc.rs`) counts a git lock as held unless its agent is live (`Policy::live`) or the task is done (`Entry::done`, T453). The agent's `ended_at` in the store is never read, and nothing unlocks a worktree when its session ends (`git::unlock` runs only from `remove`, `migrate` and `claim`). A merged, clean worktree locked by an ended agent therefore waits `max(idle, stale_lock)`, which is 7 days by default (`STALE_LOCK_DEFAULT`). Seen: `rtok-plan-log-triage` (merged, agent `ended`) and `rtok-t912` (merged, agent idle) are both kept with "locked by Claude …". Each holds 4–18 GB of `target/`. The fix is in progress in the gc session on branch `t948-gc-ended-locks`. The lifecycle side, where the lock outlives the agent, is T958.

Done: an ended, non-live agent's lock on a merged, clean worktree is reclaimed once the idle window passes (`Verdict::Reclaim`). A live agent's lock, and any lock on a dirty or unmerged worktree, still holds.

Check: rstest cases in `gc.rs` for ended+merged → remove, live → keep, ended+unmerged → keep; `just check`.
