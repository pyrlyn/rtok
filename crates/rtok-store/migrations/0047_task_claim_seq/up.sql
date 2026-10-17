-- T615: the order claims were made in. `since` is whole seconds, so two claims in one second
-- tied and the lexically first task id won; `seq` advances on every claim and re-claim.
ALTER TABLE task_claims ADD COLUMN seq BIGINT NOT NULL DEFAULT 0;
-- Rows from before this column keep the order the old query gave them (newest `since`, then the
-- lexically first id), so no tie among them is decided differently than it was.
UPDATE task_claims SET seq = (
    SELECT COUNT(*) FROM task_claims AS older
    WHERE older.since < task_claims.since
       OR (older.since = task_claims.since AND older.task_id > task_claims.task_id)
) + 1;
