-- Previous title and body, written when an upsert changes the body. checkpoint:* and
-- session:* notes are not versioned (the writer skips those kinds).
CREATE TABLE note_versions (
    id      INTEGER PRIMARY KEY,
    note_id INTEGER NOT NULL REFERENCES notes(id),
    title   TEXT NOT NULL,
    body    TEXT NOT NULL,
    version INTEGER NOT NULL,
    ts      INTEGER NOT NULL DEFAULT (unixepoch()),
    UNIQUE (note_id, version)
);
