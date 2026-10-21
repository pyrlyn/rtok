**T36.17 a removed directory leaves the index** · — · `src/plugins/graph/watch.rs`
Do: `relevant()` accepts only supported *files*, so `rm -rf src/<dir>` never reaches `run_changed` and rows for the deleted files keep being served (`callers`/`impact` name them; `symbol` prints an empty body). Treat a non-relevant, non-`.git` event path as a rescan trigger.
Check: deleting a directory removes its rows without a full walk being needed for the call that follows. `cargo test --lib graph::watch`, all 10 pass, including `removed_directory_drops_index_rows` (rstest).
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
