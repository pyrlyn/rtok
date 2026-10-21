**T36.5 `read` cap includes its own marker** · — · `src/plugins/read/mod.rs`
Do: `cap()` returns `max_chars` characters *plus* the `… archived <id> …` marker, so every capped read overshoots the configured cap.
Check: a fixture over `plugins.read.max_chars` returns at most `max_chars` characters including the marker, and still carries a working archive id. `cargo test --lib plugins::read::tests`, 7/7 pass, including `cap_includes_marker_in_max_chars` (rstest cases).
Complexity: 1/5
Status: done 2026-09-11 · Model: Composer 2.5
