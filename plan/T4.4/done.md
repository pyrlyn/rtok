**T4.4 re-read dedup** · T4.2 · `src/plugins/read/cache.rs`
Do: on `read`, hash content; if same session already returned this sha256 for this path and the same mode/range → return `unchanged since <archive_id> (N lines)`; record measurement. Invalidate on PostToolUse(Edit|Write) for that path.
Check: two identical reads → second response < 80 chars and a measurement row; edit fixture between reads → full content again.
Status: done 2026-09-02 · Check: `two_identical_reads_second_is_short`; `edit_fixture_between_reads_is_full`. `make check` green. Deviation: also `mod.rs`, `store/mod.rs` (`clear_read_cache`). Archive id is 64 hex so the hit line uses an 8-char prefix to stay under 80 chars; full id is `Measurement.ref_id`.
