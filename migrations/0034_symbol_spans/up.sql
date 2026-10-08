-- T454: the byte span of a tag, so a cut definition can be read back without a second copy
-- of the file. content_hash is sha256 of file_bytes[start_byte..end_byte]. Existing rows
-- keep 0/'' until the next index; INDEX_VERSION bumps that rebuild.
ALTER TABLE symbols ADD COLUMN start_byte INTEGER NOT NULL DEFAULT 0;
ALTER TABLE symbols ADD COLUMN end_byte INTEGER NOT NULL DEFAULT 0;
ALTER TABLE symbols ADD COLUMN content_hash TEXT NOT NULL DEFAULT '';
