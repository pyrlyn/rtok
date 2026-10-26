-- T442: the task this agent has claimed, so SessionStart can name it without calling GitHub.
-- The Markdown file or the issue is the truth. This row is the same-machine record the hook
-- reads: one claim per task, the newest `since` for an agent is the line it is shown.
CREATE TABLE IF NOT EXISTS task_claims (
    project TEXT NOT NULL,
    task_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    title TEXT NOT NULL,
    since INTEGER NOT NULL,
    PRIMARY KEY (project, task_id)
);
