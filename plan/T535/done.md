Source-confirmed on `0b14b88e`: [`read_with`](src/plugins/read/mod.rs#L171) calls `cap`, which passes `None` to [`cap_impl`](src/plugins/read/mod.rs#L270) and writes no Measurement. The subsequent render measurement only handles map/signatures/stripped modes. A fresh `full` or `lines` read exceeding `read.max_chars` is archived and shortened but produces no read saving row. Generic MCP token rows compare the arguments with the response and do not repair this missing Measurement. T300 covers outlines and stripped output; search/tree already use `cap_recording`.

Execution plan:

1. In a fresh session, read a fixture above the cap in full and ranged modes; assert one Measurement with the full rendered view as before and the returned text, including its marker, as after.
2. Reuse the existing cap recorder at the responsible path; retain a single measurement for map/signatures/stripped, dedup and delta paths.
3. Verify an uncapped full read does not acquire a fictitious saving, and the capped archive remains expandable.

Check: capped fresh full/lines reads have exactly one correct Measurement, existing rendering/dedup accounting does not double count, and `just check` passes.

Result: `read_with` records a `read_cap` row through the existing `cap_recording` when a fresh `full` or `lines` view is cut by `max_chars` (before = rendered view, after = returned text with its marker, `ref_id` = the archive that expands). `map`/`signatures`/`stripped` keep their single raw-vs-output row; an uncapped read, dedup and delta paths record nothing extra.

Check: `a_capped_fresh_read_records_one_row` (full and lines) failed before the change and passes after, including an uncapped read adding no row and the archive id expanding to the full view; `plugins::read` 93/93, `plugins_e2e` 22/22, `replay_bench` 2/2; clippy clean.

Status: done 2026-10-12
Model: Claude Code / sonnet-5.5
