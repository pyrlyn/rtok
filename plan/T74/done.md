Do (2026-09-21): The card's assumed mechanism ("key-injection → frame-assert waits with no internal deadline") does not exist — both tests are synchronous. The real mechanism, found by timing and `sample`:
- `space_toggles_the_selected_plugin_through_config_set` was **not hermetic**: `Config::load_from` expands `~` against the real `$HOME` (`expand` → `env_user_home`), so `stats.transcripts_dir` stayed `~/.claude/projects` and every `model::snapshot` in the test parsed the developer's real session JSONL — ≈30 s CPU per snapshot, ≈150 s for the test standalone, >180 s under suite load (the observed nextest kill). New shared helper `tui::app::tests::hermetic` points the doctor paths **and `stats.transcripts_dir`** at the temp home; used by `config()`, `cursor_on_plugin`, the toggle test, `fresh_store`, `logged`, and the unreadable-store test. All 36 tui tests: 0.18 s total (toggle alone was 150 s).
- `otel::hooks_stay_fast_with_an_unreachable_endpoint` now re-measures both interleaved configs to a deadline (60 s debug / 120 s release) instead of deciding on one sample set: a load blip lands on both sides of a retry, a real regression still fails every attempt.

The same leak sat in `tests/web.rs` (`serve`, `snapshot_error_when_store_path_is_a_directory`) — both now hermetic too; `ws_set_accepts_plugin_enabled` dropped from >120 s to 87 s (its remaining cost is the snapshot-TTL wait, green before and after).

Follow-up filed as I-87: the same transcripts parse makes production `rtok tui`/`rtok web` freeze ~36 s per 30 s doctor-TTL window on a heavy history (out of T74 scope; plan change proposed, not implemented).

Check: both tests green repeatedly; tui suite sub-second; `just check`.

Check result (2026-09-21): full `just check` green in the isolation worktree — 1060 passed, 3 skipped, EXIT=0; tui:: 36/36 in 0.183 s, otel latency test 1/1.
