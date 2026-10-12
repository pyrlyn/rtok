-- T385.9.1: the P28 compressor's summary of an archived tool result, one per archive id and
-- compressor version (prompt hash + model). First writer wins, so the text the live zone shows
-- never changes once stored and the prompt cache survives. The archive row stays the original.
CREATE TABLE archive_summaries (
    archive_id TEXT NOT NULL REFERENCES archive(id),
    version TEXT NOT NULL,
    summary TEXT NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    PRIMARY KEY (archive_id, version)
);
