P29 recall matched on the prompt text only; a note about `src/proxy/semantic_cache.rs` was not preferred when the session was editing that file. Migration 0033 adds `note_files (note_id, path)`. `mem_save` and the `remember:` hook save link a note to the existing project files its body names and to the session checkpoint's paths (`checkpoint::last_paths`), root-relative and never outside the root. `prompt_recall` fuses the FTS hits with the notes linked to files the session read (`read_cache`) or the prompt names, through `rrf_merge_lists` (`rrf_merge` is a two-list wrapper). The linked list is scoped to the project plus unbound notes, and any error in it leaves the text hits. SDK: `Notes::set_note_files`, `Notes::notes_for_files`, `ReadCache::read_cache_keys`, all with default bodies. No config keys.

Check: `cargo test --lib -- plugins::memory store::embed store::note_files schema_matches migrations_list`; `cargo test --test p29_memory` (file-context cases: recall@5 4/4, linked note first, existing cases unchanged); `just check`.
Result: a linked note ranks above an equally text-matching unlinked note once its file is read (`a_note_linked_to_a_read_file_outranks_an_equal_text_match`); nextest 2731 passed. #837.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
