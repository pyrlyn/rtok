Runtime half of the same request: a host may fire two events for one call (Cursor: `afterMCPExecution` and `postToolUse`; Claude Code with both the plugin and settings-file hooks), and each processing adds a `Measurement` row and an archive entry, so savings double-count (D3).

Plan: `tests/one_call_once.rs` — per host payload fixture set, feed every event the host fires for one MCP call and one Bash call through `rtok hook` in a temp home, then assert exactly one `Measurement` row and at most one archive entry per call (query through the existing store API, no raw SQL). Also a Claude home with the plugin and leftover settings-file hooks: one PostToolUse call still yields one row.

Result: `tests/one_call_once.rs` feeds every delivery of one call through `rtok hook` in a temp home and asserts the later ones add no `Measurement` row. Cursor `postToolUse` plus `afterMCPExecution` for one long MCP result was already one row (`afterMCPExecution` is audit-only). A Claude hook delivered twice (plugin plus leftover settings-file hooks) was not: a `PreToolUse` Read delta recorded a row per delivery. Fix: the hook sets `Runtime::once` = `<event>:<tool_use_id>`; `Store::insert_measurement_once` stamps `measurements.once_key` (with plugin, kind and ref) and drops a conflicting insert; migration 0021 adds the column and a UNIQUE index. Rows from other surfaces keep a NULL key and are unaffected.

Deviation: the card planned a test only; the test went red on the double `PreToolUse`, so the fix landed with it. Archive entries needed nothing: `archive` is already keyed by the body's sha256.

Check: `cargo nextest --test one_call_once` 2/2 (red before the fix: `["PreToolUse"] recorded the call again`); `just check` green.

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
