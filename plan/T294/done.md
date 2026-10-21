A later sync lives in a private repo. That repo was not in the local tree or in `gh repo list` / `gh search` on 2026-09-27 (the only cloud-sync hit was Fern in `listepo/budget-app`, a different product). This repo does not grow a protocol (D8). `export.rs` writes `{kind, title, body, project}` and drops `id`, timestamps, `retired`, `superseded_by` and `pinned`, so a replicator cannot apply a tombstone.

Plan:
1. Worktree `_worktrees/rtok-T294`.
2. Export and import round-trip `id`, `ts`, `retired`, `superseded_by` and `pinned` beside the fields already written. Old lines still import. `checkpoint:*` and `session:*` rows stay out. A retired row stays a tombstone. SQLite remains the source of truth.
3. No HTTP client, no hub, no cursor protocol. When the private repo's task is named, its identity and conflict rules get a row in `research.md` §27; they do not change this export until the creator says so.

Check: a round-trip fixture keeps a tombstone and a pin, an old three-field line still imports, and a `checkpoint:` row is absent from the file; `just check`.

**Result (2026-10-04).** `memory export` rows carry `id`, `ts`, `retired`, `superseded_by` and `pinned`, so a retired note travels as a tombstone and import writes `retired` back: `src/plugins/memory/export.rs` (`export_round_trips_tombstone_and_pin`) over `Store::list_export_notes` and `Store::insert_portable_note_if_absent` in `src/store/mod.rs`.
