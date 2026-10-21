-- T952.2: when `rtok worktree finish` handed the worktree to review. `rtok mcp` closes the task
-- and removes the worktree once its branch is merged; a new claim on the path clears it.
ALTER TABLE worktree_claims ADD COLUMN finished_at INTEGER;
