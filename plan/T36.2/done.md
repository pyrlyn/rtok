**T36.2 toon persists a live-zone decision and archives the original string** · T36.1 · `src/plugins/toon/mod.rs`
Do: `rewrite_block` never calls `archive_decision`/`put_archive_decision`, so `expand <toon-id>` does not stick (the next request re-encodes) and `expand::fetch` never records the expand Measurement; it also archives `serde_json::to_vec(&table)` — a re-serialised copy — while toon/README.md promises the original.
Check: `rtok expand <toon-id>` freezes the id for the next request and increments the toon expand row; the archived bytes equal the original text. `cargo test --lib toon`, 9/9 pass; `cargo test --lib expand`, 9/9 pass; `archived_bytes_match_original_text`; `expand_freezes_id_and_records_toon_expand_row`.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
