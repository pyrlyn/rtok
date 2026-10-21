T304 (PR #480) made `Store::memory_note_aggs` skip `session:%` and tightened `checkpoint%` to `checkpoint:%`, the same filters as `list_notes` / `list_note_titles`. Its test `memory_status_excludes_session_handoff_notes` covers the `session:` row; nothing covered the tightened `checkpoint:` pattern, which used to drop any real kind that merely starts with `checkpoint`.

Result: `tests/memory_status.rs::note_aggs_skip_session_and_checkpoint_kinds` stores `decision`, `session:s1`, `session:s2`, `checkpoint:c1` and `checkpointer` notes and asserts the aggregates list only `checkpointer` and `decision`: it fails on the old `checkpoint%` pattern and on a missing `session:%` filter.

Check: fails before T304 (lists `session:s1` / `session:s2`, drops `checkpointer`); `cargo nextest run --test memory_status` on `main` after #480: 4 passed.

Status: done 2026-09-28
Model: Claude Code / claude-opus-5-5

---
