Found 2026-09-22 in the core pass: `checkpoint::write` (`src/plugins/checkpoint.rs:202-212`) `read_to_string`s the entire JSONL transcript (hundreds of MB on real sessions) and `extract` walks every line inside `rtok hook`; `attach_ids` (:227-249) and `offer_session` (:258-280) then open a **second/third** `Store` on the same SQLite file even though the hook `Runtime` holds one — adding lock traffic exactly where T200 hurts (SessionStart with `startup_recall` does the extra open too). Unbounded memory + O(transcript) CPU + connection churn on PreCompact/SessionEnd/SessionStart.

Plan: stream-extract with `BufRead::lines`, keeping only the bounded state `extract` retains (prompts, errors, path set, optional tail); route `attach_ids`/`offer_session` through the `Ctx`'s store via two capability methods (`session_live_archives`, `latest_session_note`) instead of `Store::open`.

Check: `session_end_on_a_large_transcript_is_bounded` — a 50 MB generated transcript through `hooks::run("SessionEnd", …)` completes < 100 ms with the same note body as today; a counter asserts `Store::open` runs once per hook run; `checkpoint_fixture_has_three_paths_and_compact_injects_under_budget` and `session_end_note_and_startup_recall` unchanged; `just test` green.

Do (2026-09-24): `checkpoint::extract_path` now streams the transcript line by line (`extract_lines_with`) and keeps only bounded state: the last 20 prompts in a `VecDeque`, the last 8 errors, and the path/skill sets. `worth_parsing` skips the serde parse on `type:"user"` tool_result lines and on lines with no path or error spelling. `attach_ids` and `offer_session` read through the new SDK capabilities `Archive::session_live_archives` and `Notes::latest_session_note`, both fail-open by default and served by `Runtime`'s open store, so a hook run opens SQLite once. Tests:
- `session_end_on_a_large_transcript_is_bounded`: a 50 MB transcript shaped like a real session gives one `Store::open` and the same note as `extract` (~57 ms release, ~960 ms debug).
- `prefilter_is_a_true_superset_on_fixtures_and_a_large_transcript`: output with the filter equals output without it.

`just check` green, 1575 tests.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
