SessionEnd stamped `ended_at` and left the git lock that names the agent. `gc` then treated that lock as held, so a merged, clean worktree waited out `stale_lock` (7 days) unless T949's ended-agent rule reclaimed it.

`unlock_ended` reads the checkout's `locked` file. After `end_agent` succeeds, SessionEnd runs one `git worktree unlock` when that file names this agent. A lock that names another agent, or none, stays, so a sub-agent that shares the parent's worktree does not unlock it while the parent is live. A missing checkout is not an error. When the store is busy the event is still deferred, and the child unlocks. A lock that the unlock did not release still follows T949.

Check: `locked_by_matches_only_the_agent_named_on_the_lock`; `session_end_unlocks_that_agents_worktree_and_gc_drops_it` (A unlocked, B kept, dry-run `gc --idle 0h` plans `remove` / `merged, clean and idle` for A); `a_subagents_session_end_leaves_the_parents_lock`. `just check` green.

Status: done 2026-10-22
Model: Grok / grok-4.7
