-- T433: every hook stdin repeats the same session fields (`session_id`, `transcript_path`,
-- `cwd`, ...). They are saved once per distinct value set here, as one compact JSON object,
-- and `call_io.request_json` keeps only the event's own fields. NULL on every existing row:
-- those still hold the full body and read back unchanged.
CREATE TABLE IF NOT EXISTS hook_sessions (
    id INTEGER PRIMARY KEY,
    fields TEXT NOT NULL UNIQUE
);
ALTER TABLE call_io ADD COLUMN hook_session_id INTEGER NULL REFERENCES hook_sessions(id);
