-- T501: the hook call (`<event>:<tool_use_id>`) an observation came from, so a call the host
-- delivers again after the few-second `dedup` window still stores one row. NULL for a call
-- without an id and for rows written before this column; a UNIQUE index keeps NULLs distinct.
ALTER TABLE observations ADD COLUMN call_key TEXT;
CREATE UNIQUE INDEX observations_call_key ON observations (call_key);
