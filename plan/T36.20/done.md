**T36.20 archive rows and inline bodies keep their attribution** · — · `src/store/mod.rs`
Do: (a) `spill` writes every `call_io` body with `archive.session = ""`, so archived bodies are unattributable — thread the session through; (b) inline bodies are stored from `String::from_utf8_lossy`, so `request_json` is not the byte string `request_sha256` hashes — store the bytes or hash the lossy form; (c) `insert_measurement` clamps with `unwrap_or(i64::MAX)`, a silently wrong saving where a checked conversion should error.
Check: an archived `call_io` body carries its session; the recorded sha256 matches the stored text; an out-of-range estimate is an error, not a clamp.
Complexity: 2/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test --lib store` — `spill_archive_carries_session`, `inline_sha256_matches_stored_text`, `insert_measurement_rejects_out_of_range_estimates` pass (rstest); T36.2 `live_zone_pointer` retained.
