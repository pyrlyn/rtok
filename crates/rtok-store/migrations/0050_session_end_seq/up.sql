-- T592: the order sessions ended in. The OTel exporter resumed a same-second tie by counting
-- rows ordered by id, so a session that ended later with a lexically lower id shifted an
-- already-posted row past the count and was never selected. `end_seq` only grows: every end
-- takes the next value, so a late session always sorts after everything already posted.
ALTER TABLE sessions ADD COLUMN end_seq BIGINT NOT NULL DEFAULT 0;
CREATE INDEX sessions_end_seq ON sessions(end_seq);
UPDATE sessions SET end_seq = ranked.rn
FROM (
    SELECT id, ROW_NUMBER() OVER (ORDER BY ended_at, id) AS rn
    FROM sessions WHERE ended_at IS NOT NULL
) AS ranked
WHERE sessions.id = ranked.id;
-- The old (ended_at, tail) mark cannot say which tied sessions were posted. Resume in front
-- of the whole second it covered: the few repeats carry the same span ids and bytes, which
-- the backend dedupes, where a guess could lose a session for good.
INSERT OR REPLACE INTO otel_export (stream, mark)
SELECT 'sessions_seq', COALESCE(MAX(end_seq), 0) FROM sessions
WHERE ended_at < (SELECT mark FROM otel_export WHERE stream = 'sessions');
DELETE FROM otel_export WHERE stream IN ('sessions', 'sessions_tail');
