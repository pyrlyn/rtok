-- T374: the files a memory note is about, root-relative. Filled when a note is saved (paths in
-- its body that exist under the project root, plus the session checkpoint's paths) and read by
-- prompt recall, which boosts notes linked to files the session has read or the prompt names.
-- The cascade drops the links with the note; the path index serves the reverse lookup.
CREATE TABLE IF NOT EXISTS note_files (
    note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    path TEXT NOT NULL,
    PRIMARY KEY (note_id, path)
);
CREATE INDEX IF NOT EXISTS note_files_path ON note_files (path);
