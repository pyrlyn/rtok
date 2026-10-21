**T35.4 the watcher indexes what changed** · T8.16 · `src/plugins/graph/watch.rs`, `src/plugins/graph/index.rs`
Do: `pump` keeps the relevant event paths; `settle` indexes only those (the stat and sha gates per path; rows dropped for a vanished path). A full walk only on a rescan or overflow event.
Check: `pump` and FSEvents watch tests green (`graph::watch` 9/9). New `run_changed_indexes_only_touched_file` (20 files, `read == 1`). Vanished paths use `mark_symbols_stale`, never `delete_symbols_missing` on a partial keep set. Overflow (>1024) and `need_rescan` still full-walk. One edit on the 3 000-file fixture via `run_changed` was `read=1` in 1.66 ms (2026-09-11, `p8c_one_edit_reads_one_file_under_10ms`; `tests/graph_bench.rs`).
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
