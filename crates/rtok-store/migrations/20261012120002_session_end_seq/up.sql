-- T592: the OTel session export cursor is a durable sequence, not a rank among the sessions that
-- ended in the same second. `end_seq` is stamped by `end_session` from the table's own maximum,
-- so a session that ends late gets a number past everything already exported whatever its id.
ALTER TABLE sessions ADD COLUMN end_seq INTEGER;

-- Existing ended sessions are numbered in the order the old cursor walked them.
UPDATE sessions SET end_seq = ranked.n
FROM (
    SELECT id, ROW_NUMBER() OVER (ORDER BY ended_at, id) AS n
    FROM sessions
    WHERE ended_at IS NOT NULL
) AS ranked
WHERE sessions.id = ranked.id;

CREATE INDEX sessions_end_seq ON sessions (end_seq);

-- The old mark was a timestamp plus a count of sessions delivered inside that second. A count
-- cannot say WHICH of the tied sessions were delivered (a late one with a lower id may have been
-- skipped), so the new mark stops before the whole watermark second: those sessions are sent
-- again, with the same span ids and bytes, instead of risking another loss.
UPDATE otel_export SET mark = (
    SELECT COUNT(*) FROM sessions WHERE ended_at IS NOT NULL AND ended_at < otel_export.mark
)
WHERE stream = 'sessions';

DELETE FROM otel_export WHERE stream = 'sessions_tail';
