Source-confirmed on `0b14b88e`: [`mem_revise`](src/plugins/memory/mod.rs#L598) passes `old.project.as_deref()` to `mem_save`. For a global note (`project = NULL`), that becomes `None`, which [`mem_save`](src/plugins/memory/mod.rs#L430) interprets as infer the current working directory's project. Global notes are supported by [`memory import`](src/plugins/memory/import.rs#L30), whose optional project is stored without cwd inference. Import a global note, then revise it while inside a repository: the replacement becomes repository-scoped and the global note is retired, even when the title is unchanged. It may also upsert an existing same-title project note.

Execution plan:

1. Seed a global note and a separate same-title project note. Revise the global id from a repository cwd with both unchanged and changed titles.
2. Separate an already-resolved optional project from the user-facing infer-project default; reuse one save implementation while preserving the old note's exact scope.
3. Cover global, explicit-project and inferred-project creation plus revision; keep privacy stripping, embeddings and note history on the same write path.

Check: global revisions stay global, unrelated project notes are untouched, ordinary creation still infers cwd, and `just check` passes.

**Docs/checkpoint audit (T547).** T548–T553 are confirmed against `99b6753d`
(product source unchanged from `0b14b88e`). Address the private-text persistence
case first; the remaining fixes are independent except that T549/T550 share the
local documentation ingestion path. Related completed feature tasks are cited
below; these are newly identified failure cases, not copies of those features.

Result: The shared save path is `save_scoped`, which takes a resolved project: `mem_save` infers it from the cwd, `mem_revise` passes the old note's exact scope, so a global note stays global.

Check: `revising_a_global_note_keeps_it_global` failed before and passes after; `plugins::memory` green.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
