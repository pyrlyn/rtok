-- T471: the byte span of a symbol, plus the signature and doc line `explore` searches.
ALTER TABLE symbols ADD COLUMN start_byte INTEGER NOT NULL DEFAULT 0;
ALTER TABLE symbols ADD COLUMN end_byte INTEGER NOT NULL DEFAULT 0;
ALTER TABLE symbols ADD COLUMN content_hash TEXT NOT NULL DEFAULT '';
ALTER TABLE symbols ADD COLUMN signature TEXT NOT NULL DEFAULT '';
ALTER TABLE symbols ADD COLUMN doc TEXT NOT NULL DEFAULT '';

CREATE VIRTUAL TABLE symbols_fts USING fts5(
    name,
    signature,
    doc,
    content = 'symbols',
    content_rowid = 'id'
);

CREATE TRIGGER symbols_fts_ai AFTER INSERT ON symbols BEGIN
    INSERT INTO symbols_fts (rowid, name, signature, doc)
    VALUES (new.id, new.name, new.signature, new.doc);
END;
CREATE TRIGGER symbols_fts_ad AFTER DELETE ON symbols BEGIN
    INSERT INTO symbols_fts (symbols_fts, rowid, name, signature, doc)
    VALUES ('delete', old.id, old.name, old.signature, old.doc);
END;
CREATE TRIGGER symbols_fts_au AFTER UPDATE ON symbols BEGIN
    INSERT INTO symbols_fts (symbols_fts, rowid, name, signature, doc)
    VALUES ('delete', old.id, old.name, old.signature, old.doc);
    INSERT INTO symbols_fts (rowid, name, signature, doc)
    VALUES (new.id, new.name, new.signature, new.doc);
END;

INSERT INTO symbols_fts (symbols_fts) VALUES ('rebuild');
