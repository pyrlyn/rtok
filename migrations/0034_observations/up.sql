-- T454: synthetic PostToolUse observations (agentmemory compress-synthetic shape, no LLM).
-- Narrative is scrubbed and truncated; full tool output lives in archive (D4). Retire never DELETE.
CREATE TABLE observations (
    id          INTEGER PRIMARY KEY,
    ts          INTEGER NOT NULL DEFAULT (unixepoch()),
    session     TEXT    NOT NULL,
    project     TEXT,
    kind        TEXT    NOT NULL,
    title       TEXT    NOT NULL,
    narrative   TEXT    NOT NULL,
    files       TEXT    NOT NULL DEFAULT '',
    archive_id  TEXT,
    importance  INTEGER NOT NULL DEFAULT 5,
    confidence  REAL    NOT NULL DEFAULT 0.3,
    uses        INTEGER NOT NULL DEFAULT 0,
    last_used   INTEGER NULL,
    retired     INTEGER NULL,
    pinned      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX observations_session ON observations (session, ts);
CREATE INDEX observations_project ON observations (project, ts);

CREATE VIRTUAL TABLE observations_fts USING fts5 (
    title, narrative, content = 'observations', content_rowid = 'id'
);
CREATE TRIGGER observations_ai AFTER INSERT ON observations BEGIN
    INSERT INTO observations_fts (rowid, title, narrative)
    VALUES (new.id, new.title, new.narrative);
END;
CREATE TRIGGER observations_ad AFTER DELETE ON observations BEGIN
    INSERT INTO observations_fts (observations_fts, rowid, title, narrative)
    VALUES ('delete', old.id, old.title, old.narrative);
END;
CREATE TRIGGER observations_au AFTER UPDATE ON observations BEGIN
    INSERT INTO observations_fts (observations_fts, rowid, title, narrative)
    VALUES ('delete', old.id, old.title, old.narrative);
    INSERT INTO observations_fts (rowid, title, narrative)
    VALUES (new.id, new.title, new.narrative);
END;
