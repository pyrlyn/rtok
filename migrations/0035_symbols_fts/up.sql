-- T454: full-text over definition name, the definition line, and the doc comment above it.
-- External content, same shape as notes_fts. Only definitions are indexed, so references
-- do not dominate bm25. The backfill covers rows already in the table: a later DELETE
-- trigger must find an FTS row, and a fresh database inserts nothing here.
ALTER TABLE symbols ADD COLUMN signature TEXT NOT NULL DEFAULT '';
ALTER TABLE symbols ADD COLUMN doc TEXT NOT NULL DEFAULT '';

CREATE VIRTUAL TABLE symbols_fts USING fts5 (
    name,
    signature,
    doc,
    content = 'symbols',
    content_rowid = 'id'
);

CREATE TRIGGER symbols_ai AFTER INSERT ON symbols
WHEN new.is_def = 1 AND new.name != ''
BEGIN
    INSERT INTO symbols_fts (rowid, name, signature, doc)
    VALUES (new.id, new.name, new.signature, new.doc);
END;

CREATE TRIGGER symbols_ad AFTER DELETE ON symbols
WHEN old.is_def = 1 AND old.name != ''
BEGIN
    INSERT INTO symbols_fts (symbols_fts, rowid, name, signature, doc)
    VALUES ('delete', old.id, old.name, old.signature, old.doc);
END;

CREATE TRIGGER symbols_au_del AFTER UPDATE ON symbols
WHEN old.is_def = 1 AND old.name != ''
BEGIN
    INSERT INTO symbols_fts (symbols_fts, rowid, name, signature, doc)
    VALUES ('delete', old.id, old.name, old.signature, old.doc);
END;

CREATE TRIGGER symbols_au_ins AFTER UPDATE ON symbols
WHEN new.is_def = 1 AND new.name != ''
BEGIN
    INSERT INTO symbols_fts (rowid, name, signature, doc)
    VALUES (new.id, new.name, new.signature, new.doc);
END;

INSERT INTO symbols_fts (rowid, name, signature, doc)
SELECT id, name, signature, doc FROM symbols WHERE is_def = 1 AND name != '';
