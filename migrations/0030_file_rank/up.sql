-- T370: the graph plugin's file graph and its global PageRank, one JSON document per root.
-- It is rebuilt whenever an index run changes the root and read by the SessionStart hook in one
-- row, so the hook never scans `symbols`. The document holds only what the index knows; the
-- session's personalization never reaches it.
CREATE TABLE IF NOT EXISTS file_rank (
    root TEXT PRIMARY KEY NOT NULL,
    graph TEXT NOT NULL
);
