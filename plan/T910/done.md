T672 shortened the command trailer. The hook budget marker, the skill deny, the guard duplicate line, the handoff brief, the MCP wrap trailer and the `rtok expand` omitted-line marker still printed the full 64-character id. `expand` already resolves an 8-character prefix. A prefix the caller already lengthened (9–63 hex chars) stays as sent. The measurement `ref_id` stays the full id. The hook marker is priced with an 8-character placeholder, the same length as the id it will print.

Check: `cap_budget_gives_back_lines_so_the_marker_fits` — the printed id is 8 hex chars and `expand` of that prefix rebuilds the dropped lines. `oversized_body_is_denied_with_map_and_pointer_under_budget` resolves the printed prefix to the archived skill body. `shorten_result_records_archive_mcp_above_threshold` prints 8 chars and keeps the full id in `ref_id`. `a_full_archive_id_in_the_omitted_marker_prints_eight_hex_chars` prints 8 hex chars in the expand cap marker.

Status: done 2026-10-19
Model: Cursor / grok 4.7
