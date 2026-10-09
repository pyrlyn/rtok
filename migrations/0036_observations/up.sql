-- T454: a mechanical observation of one tool call. The narrative is scrubbed and capped
-- at 400 characters; the full tool output stays in `archive` and is read with `expand`.
-- `dedup` is the sha256 of session, tool and narrative so a repeated hook in the same
-- few seconds does not insert a second row.
CREATE TABLE observations (
    id         INTEGER PRIMARY KEY,
    ts         INTEGER NOT NULL DEFAULT (unixepoch()),
    session_id TEXT    NOT NULL,
    project    TEXT,
    obs_type   TEXT    NOT NULL,
    title      TEXT    NOT NULL,
    narrative  TEXT    NOT NULL,
    dedup      TEXT    NOT NULL
);
CREATE INDEX observations_project_ts ON observations (project, ts);
CREATE INDEX observations_dedup ON observations (session_id, dedup, ts);

CREATE TABLE observation_files (
    observation_id INTEGER NOT NULL REFERENCES observations(id) ON DELETE CASCADE,
    path           TEXT    NOT NULL,
    PRIMARY KEY (observation_id, path)
);
CREATE INDEX observation_files_path ON observation_files (path);

CREATE VIRTUAL TABLE observations_fts USING fts5 (
    title, narrative, content = 'observations', content_rowid = 'id'
);
CREATE TRIGGER observations_ai AFTER INSERT ON observations BEGIN
    INSERT INTO observations_fts (rowid, title, narrative) VALUES (new.id, new.title, new.narrative);
END;
CREATE TRIGGER observations_ad AFTER DELETE ON observations BEGIN
    INSERT INTO observations_fts (observations_fts, rowid, title, narrative)
    VALUES ('delete', old.id, old.title, old.narrative);
END;
CREATE TRIGGER observations_au AFTER UPDATE ON observations BEGIN
    INSERT INTO observations_fts (observations_fts, rowid, title, narrative)
    VALUES ('delete', old.id, old.title, old.narrative);
    INSERT INTO observations_fts (rowid, title, narrative) VALUES (new.id, new.title, new.narrative);
END;
