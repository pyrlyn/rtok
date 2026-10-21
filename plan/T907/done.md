T672 shortened the command trailer and the read-cache follow-up is T906. A capped `graph` answer still printed the full 64-character id on `N more, expand <id>` and on a definition body past the line budget (`… N more lines, expand <id>`). `expand` already resolves an 8-character prefix. The measurement `ref_id` stays the full id.

Check: `five_hundred_hits_are_capped_with_archive_id` — the printed id is 8 hex chars, the ledger `ref_id` is the full id, and `get_archive` of the resolved id returns the uncapped text. The same prefix is what `long_body_archives_the_uncut_span` and `a_long_answer_is_cut_with_an_id_that_pages_the_rest` look up.

Status: done 2026-10-19
Model: Cursor / grok 4.7
