-- T441.3: one task id counter per project, shared by every checkout, worktree and agent host on
-- the machine. `parent` is '' for the top-level counter (`R12`) or the parent's number path
-- (`2` for `R2.1`); the prefix is not part of the key, so renaming it keeps the numbering.
-- Allocation is one `BEGIN EXCLUSIVE` read-increment-write, atomic across processes.
CREATE TABLE IF NOT EXISTS task_counters (
    project TEXT NOT NULL,
    parent TEXT NOT NULL DEFAULT '',
    last INTEGER NOT NULL,
    PRIMARY KEY (project, parent)
);
