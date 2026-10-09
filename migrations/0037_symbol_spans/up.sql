-- T474: byte span of each symbol, so a cut body can be read back from the file.
-- content_hash is the sha256 of file bytes [start_byte, end_byte).
ALTER TABLE symbols ADD COLUMN start_byte INTEGER NOT NULL DEFAULT 0;
ALTER TABLE symbols ADD COLUMN end_byte INTEGER NOT NULL DEFAULT 0;
ALTER TABLE symbols ADD COLUMN content_hash TEXT NOT NULL DEFAULT '';
