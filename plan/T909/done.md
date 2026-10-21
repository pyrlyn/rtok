T672 shortened the command trailer. The archive live-zone pointer still printed a 12-character label and the full 64-character id in `expand(<id>)`, and the compress view did the same. `expand` already resolves an 8-character prefix. A decision already stored is replayed as written, so a cached prefix does not change. The measurement `ref_id` stays the full id.

Check: `pointer_prints_an_8_char_id_and_the_ledger_keeps_the_full_one` — the printed id is 8 hex chars, the ledger `ref_id` is the full id, and `resolve_id` of that prefix returns the archived body. `a_stored_full_id_pointer_is_replayed_verbatim` keeps a pointer that already holds the full id. The skill pointer, the compress view and a capped MCP `expand` use the same prefix. `proxy_session_saves_over_the_floor` matches the printed prefix in the body upstream received.

Status: done 2026-10-19
