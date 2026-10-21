`guard` records denied repeats with `est_after: 0`, but no test checked that the row's `before` is the size of what was denied. The `read` modes had no minimum saving.

Result: guard unit test `deny_measurement_before_bytes_and_est_before_match_the_avoided_payload` — `before_bytes` equals the archived body the repeat would have returned, `est_before` the documented `bytes / 4` heuristic, `after` 0. `tests/plugins_e2e.rs` `read_modes_keep_a_saving_floor` over `tests/fixtures/read_modes_sample.rs` (1350 tokens): `read` `map` measured 95.1 % (floor 90.1), `read` `signatures` 88.8 % (83.8), `search` 88.9 % (83.9). Found: only `mode = "stripped"` records a `Measurement`; `map`, `signatures`, `search` and `tree` record none, so their saving never reaches `rtok stats` — filed as I-100.

Check: `cargo nextest run -p rtok --test plugins_e2e -E 'test(read_modes_keep_a_saving_floor) | test(read_stripped)'` 2/2; the guard test 1/1; `cargo clippy -p rtok --lib --test plugins_e2e -- -D warnings` clean.

Status: done 2026-09-27
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
