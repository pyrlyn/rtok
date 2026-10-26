-- T472: the previous title and body, kept when an upsert changes the body.
-- checkpoint:* and session:* kinds are not recorded. Recall and mem_get stay on the current row.
CREATE TABLE note_versions (
    id      INTEGER PRIMARY KEY,
    note_id INTEGER NOT NULL REFERENCES notes(id),
    title   TEXT NOT NULL,
    body    TEXT NOT NULL,
    version INTEGER NOT NULL,
    ts      INTEGER NOT NULL DEFAULT (unixepoch()),
    UNIQUE (note_id, version)
);
