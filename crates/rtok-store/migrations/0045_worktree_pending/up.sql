-- T289.5: a worktree adopted by a host's post-create script when no single agent could be named.
-- The next agent that adopts it, or whose SessionStart cwd is inside it, turns the row into a
-- `worktree_claims` row. A separate table because `worktree_claims.agent_id` is NOT NULL and
-- every reader of that table expects an agent.
CREATE TABLE worktree_pending (
    path TEXT PRIMARY KEY,
    task TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch())
);
