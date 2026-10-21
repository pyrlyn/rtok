---
status: todo
priority: P1
complexity: 2
readiness: 0%
agent:
---

# T958. Ending an agent leaves its worktree locked

Bug. SessionEnd (`src/hooks/mod.rs`, `"SessionEnd"` arm) stamps the agent's `ended_at` through `Store::end_agent` and ends the session, but leaves the worktree the agent claimed locked. `git::unlock` runs only from `worktree remove`, `migrate` and `claim` (`src/worktree/{remove,migrate,claim}.rs`). Every ended agent therefore leaves a git lock that still names it, and `gc` treats that lock as held (see T956). Seen: `rtok-plan-log-triage` (agent `ended`, lock reason "Claude Code / claude-opus-5-5 | plan-log-triage | … | agent 126b1714…"). The exception is T453: a finished task (`Entry::done`) is removed despite the lock, which is how `rtok-t904` went.

Done: when an agent's row is ended, the lock that names that agent is released, or rewritten as an ownerless "ended" record, on the worktree it held. A lock that names another agent or no agent is left alone. SessionEnd stays fail-open and inside the hook budget: the unlock is one `git worktree unlock`, deferred like `end_session` when the store is busy. Sub-agents that share the parent's worktree do not unlock it while the parent is live.

Check: a hook test where SessionEnd for agent A unlocks A's worktree and leaves B's lock; a sub-agent's SessionEnd leaves the parent's lock; `worktree gc` dry run then removes A's merged, clean, idle worktree; `just check`.
