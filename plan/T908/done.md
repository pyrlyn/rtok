T672 made `expand` resolve an 8-character prefix. A fresh read, `search`, or `tree` cut by `plugins.read.max_chars` still printed the full 64-character id inside `… archived <id> …`. The dedup and delta lines are T906; this is the cap marker. The measurement `ref_id` stays the full id. `plugins.read.max_chars` still has to be at least 100.

Check: `a_capped_fresh_read_records_one_row` — the printed id is 8 hex chars, it is a prefix of the ledger `ref_id`, and `expand` of the prefix returns the uncapped text. `cap_includes_marker_in_max_chars` looks the same prefix up.
