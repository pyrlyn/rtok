`done.md` T59.3 and `research.md` §2 said the cold graph index commits symbols and edges once per 200 files, but `src/plugins/graph/index.rs` still flushed at a hard-coded 64 and a test comment named a `SYMBOL_BATCH_FILES` constant that did not exist (found by T397). Added `const SYMBOL_BATCH_FILES: usize = 200`, used at the flush site, and recorded the 64 vs 200 measurement in `research.md` §2. The warm path and the hook budget are unchanged.

Check: `cargo test --release --test graph_bench -- --ignored --nocapture p8c_numbers`, 16 interleaved pairs of 64 and 200 at load 40-88; `cargo test -q --lib graph` (125 passed); `cargo clippy --all-targets`; `cargo fmt --check`.
Result: cold index of 3 000 files, median 360 ms at 64 files/txn vs 384 ms at 200 (within load noise); 200 kept, as T59.3 claimed; re-measure on an idle machine before tuning further. PR #850.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
