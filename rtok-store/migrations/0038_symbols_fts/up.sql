-- T474: full-text search over a definition's name, signature, and doc comment.
-- External-content FTS5, same trigger shape as notes_fts. Only definition rows are indexed.
ALTER TABLE symbols ADD COLUMN signature TEXT NOT NULL DEFAULT '';
ALTER TABLE symbols ADD COLUMN doc TEXT NOT NULL DEFAULT '';

CREATE VIRTUAL TABLE symbols_fts USING fts5 (
    name,
    signature,
    doc,
    content = 'symbols',
    content_rowid = 'id'
);

CREATE TRIGGER symbols_ai AFTER INSERT ON symbols WHEN new.is_def = 1 AND new.name != '' BEGIN
    INSERT INTO symbols_fts (rowid, name, signature, doc)
    VALUES (new.id, new.name, new.signature, new.doc);
END;

CREATE TRIGGER symbols_ad AFTER DELETE ON symbols WHEN old.is_def = 1 AND old.name != '' BEGIN
    INSERT INTO symbols_fts (symbols_fts, rowid, name, signature, doc)
    VALUES ('delete', old.id, old.name, old.signature, old.doc);
END;

CREATE TRIGGER symbols_au AFTER UPDATE ON symbols BEGIN
    INSERT INTO symbols_fts (symbols_fts, rowid, name, signature, doc)
    SELECT 'delete', old.id, old.name, old.signature, old.doc
    WHERE old.is_def = 1 AND old.name != '';
    INSERT INTO symbols_fts (rowid, name, signature, doc)
    SELECT new.id, new.name, new.signature, new.doc
    WHERE new.is_def = 1 AND new.name != '';
END;

-- Rows written before this migration have empty signature and doc. Index them so a
-- later delete trigger has a row to remove; the next index pass fills the text.
INSERT INTO symbols_fts (rowid, name, signature, doc)
SELECT id, name, signature, doc FROM symbols WHERE is_def = 1 AND name != '';
