**T36.14 live passthrough rows are not labelled tokens** · — · `src/web/model.rs`, `src/tui/view.rs`
Do: a plain-proxy row stores byte counts in `input`/`output`, and the Calls page renders their sum as `… tok` while no `usage` row exists for it.
Check: a live row shows bytes (or `-`), and a linked api_request row still shows tokens. `cargo test --lib call_size_label_distinguishes live_passthrough_rows_show_bytes_not_tokens calls_tab_lists_rows_newest_first`, all pass (rstest).
Complexity: 1/5
Status: done 2026-09-11 · Model: Composer 2.5
