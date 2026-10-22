Bug 1: `Store::list_notes` (used by `memory export`) did not filter `notes::retired.is_null()`, and the export/import JSONL line shape carries no `retired` field, so an export piped into another store's `memory import` resurrected a note the user had retired there as a live note. `memory import`'s topic-key dedup (`list_notes(None, ..)`) still needs retired rows — the `notes_topic` unique index covers a retired row too, so a local key must block an imported line whether or not it is retired — so the filter could not simply move into the shared query. Fix: `list_notes` takes an `include_retired` flag; `memory export` passes `false`, `memory import`'s dedup and its own tests pass `true`.
Bug 2: `Store::memory_note_aggs` (used by `memory status`) filtered `checkpoint%` but not `session:%`, unlike its siblings `list_notes` / `list_note_titles`. `session:<id>` kinds (`src/plugins/checkpoint.rs`'s per-session handoff note) are unique per session, so `memory status` grew one aggregate row per historical session forever. Fix: filter `session:%` alongside `checkpoint:%`, matching the siblings.

Check: new tests `plugins::memory::export::tests::retired_note_is_not_exported` and `rtok::memory_status memory_status_excludes_session_handoff_notes`; `cargo test --lib store::` (67 passed) and `--lib memory::` (30 passed), `cargo nextest run -E 'binary(memory_status)'` (3 passed) and `-E 'binary(cli_trycmd)'` / `-E 'binary(p29_memory)'` unaffected, clippy `--lib --tests -D warnings` and `fmt --check` clean.

Status: done 2026-09-28
Model: Claude Code / claude-sonnet-5

---
