I-100 (found by T299): only `mode = "stripped"` recorded a `Measurement`, so the saving of `map`, `signatures`, `search` and `tree` never reached `rtok stats`. Creator's choice (2026-09-27): `map`/`signatures` measure against the whole file they replace; `search`/`tree` record only when `max_chars` cuts their output, against their own full output — there is no honest "before" for them beyond that.

Result: `read_with` records kind `map` / `signatures` (raw file vs returned text) on the fresh-render path only; the `cache::hit` and `identical_result` paths return earlier with their own `delta`/`dedup` row, so one call writes at most one row. `cap_recording` (a recording variant of `cap`) writes `search_cap` / `tree_cap` with the archive id as `ref_id` when it cuts; plain `cap` (full reads, `stripped`) records nothing, so no double count. Tests in `tests/plugins_e2e.rs`: `read_modes_keep_a_saving_floor` also checks one row per `map`/`signatures` call with bytes matching the file and the returned text, and none for an uncut `search`; `search_and_tree_cap_measurement_matches_full_and_returned_text` checks the cut rows against the archived full output.

Check: `cargo nextest run -p rtok --lib plugins::read` and `--test plugins_e2e` green; `cargo clippy -p rtok --lib --tests -- -D warnings` clean.

Status: done 2026-09-28
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
