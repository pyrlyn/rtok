-- T455: cached rustdoc items for MCP docs_query / docs_get. FTS5 external-content
-- on (path, docs), same insert/delete trigger shape as notes_fts (0001).
CREATE TABLE IF NOT EXISTS doc_crates (
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    format_version INTEGER NOT NULL,
    fetched_unix INTEGER NOT NULL,
    PRIMARY KEY (name, version)
);

CREATE TABLE IF NOT EXISTS doc_items (
    id INTEGER PRIMARY KEY,
    crate_name TEXT NOT NULL,
    version TEXT NOT NULL,
    path TEXT NOT NULL,
    kind TEXT NOT NULL,
    docs TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS doc_items_crate ON doc_items (crate_name, version);

CREATE VIRTUAL TABLE IF NOT EXISTS doc_items_fts USING fts5 (
    path,
    docs,
    content = 'doc_items',
    content_rowid = 'id'
);
CREATE TRIGGER IF NOT EXISTS doc_items_ai AFTER INSERT ON doc_items BEGIN
    INSERT INTO doc_items_fts (rowid, path, docs) VALUES (new.id, new.path, new.docs);
END;
CREATE TRIGGER IF NOT EXISTS doc_items_ad AFTER DELETE ON doc_items BEGIN
    INSERT INTO doc_items_fts (doc_items_fts, rowid, path, docs) VALUES ('delete', old.id, old.path, old.docs);
END;
CREATE TRIGGER IF NOT EXISTS doc_items_au AFTER UPDATE ON doc_items BEGIN
    INSERT INTO doc_items_fts (doc_items_fts, rowid, path, docs) VALUES ('delete', old.id, old.path, old.docs);
    INSERT INTO doc_items_fts (rowid, path, docs) VALUES (new.id, new.path, new.docs);
END;
