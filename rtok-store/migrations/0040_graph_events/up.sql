-- T329.15: the start, progress and end events of graph tool calls. Every rtok process that
-- answers a graph call appends here; `rtok web` reads `id > cursor` and pushes the rows on
-- `/ws`, which is how a call in another process reaches the page. Append-only and capped
-- by `Store::insert_graph_event` (it trims old rows), never joined into `rtok stats`:
-- `rows_json` copies the `measurements` rows the call wrote, so the page and the report
-- show the same numbers. Payloads are ids, names, paths and numbers, never source text.
CREATE TABLE graph_events (
    id INTEGER PRIMARY KEY,
    ts_ms INTEGER NOT NULL,
    call TEXT NOT NULL,
    phase TEXT NOT NULL,
    session TEXT NOT NULL,
    tool TEXT NOT NULL,
    target TEXT,
    project TEXT,
    backend TEXT,
    ok INTEGER NOT NULL DEFAULT 1,
    error TEXT,
    ms REAL,
    done INTEGER,
    total INTEGER,
    answer_tokens INTEGER,
    rows_json TEXT
);
