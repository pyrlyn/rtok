Bug. `gc::decide` (`src/worktree/gc.rs`) counted a git lock as held unless its agent was live (`Policy::live`) or the task was done (`Entry::done`, T453). The agent's `ended_at` in the store was never read, and nothing unlocks a worktree when its session ends (`git::unlock` runs only from `remove`, `migrate` and `claim`). A merged, clean worktree locked by an ended agent therefore waited `max(idle, stale_lock)`, 7 days by default. Seen: `rtok-plan-log-triage` (merged, agent `ended`) kept with "locked by Claude …", 4–18 GB of `target/` each. On the dry run after the fix it turned out to be a finished task (T453's `Verdict::Finished`): what kept it was T948's idle clock, which its `target/` kept resetting. This rule is for a merged worktree of an ended agent whose task is not marked finished.

`gc::Policy` now takes the store's ended agent ids (`Store::ended_agent_ids`, `ops::ended_ids`), the way it takes the live ones; `worktree gc` and `agents junk` both pass them. A foreign lock naming an ended agent holds a merged, clean worktree only through the idle window, then `Verdict::Reclaim` with the note "agent <id> ended". A live agent's lock, and any lock on a dirty or unmerged worktree, still holds. An idle-but-not-ended agent (`rtok-t912`) is unchanged: it still waits out `stale_lock`.

Check: `an_ended_agents_lock_holds_only_through_the_idle_window` — ended and idle → reclaim; ended but busy, unreadable, also live, dirty or unmerged, and a not-ended agent → keep.

Status: done 2026-10-20
Model: Claude Code / claude-opus-5-5
