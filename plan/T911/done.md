T672 prints an 8-character archive id. `stats` still treated a pointer as shown only when the id was 12–64 hex chars, and it keyed the match on the first 12. An `… omitted (expand <8>)` line or a `[rtok <8>]` trailer was invisible, so the expand that followed it was not a re-expand. A full-id expand of that same archive missed it too.

The matcher now accepts `names::MIN_PREFIX` (8) through 64 hex chars and keys on that prefix. A 7-character token still does not count. An older 12-character prefix still matches a full-id expand, because both keys are the first 8 characters.

Check: `expand_right_after_counts_the_eight_char_id_pointers_print` — an 8-character trailer plus `rtok expand` of that prefix and an MCP expand of the full id count as two re-expands; a 7-character token and a different archive do not. `expand_right_after_counts_expands_of_shown_ids_only` still counts a 64-character id.

Status: done 2026-10-20
Model: Cursor / grok 4.7

The creator asked to rename the folder that holds undo copies of host configs from `_backup/` to `.backup_configs/`. New copies go only to `.backup_configs/`. A `_backup/` folder left by an older rtok stays rtok's own: junk never walks it as foreign copies, and `stale_backups` / the junk review still list its generations past `setup.backup_files`. Nothing is moved.

Done: `rtok-agent-sdk` has `BACKUP_DIR = ".backup_configs"`, `LEGACY_BACKUP_DIR`, `BACKUP_DIRS` and `is_backup_dir`; `prune_backups`, `stale_backups`, `junk_kinds::found_items` and `junk_review::backup_items` accept both names. Docs (en/ru/uk), `default.toml`, the `doctor --yes` help, comments and trycmd snapshots name `.backup_configs/`.

Check: `backup_writes_to_backup_configs_not_the_legacy_folder`, `stale_backups_lists_only_generations_past_the_cap` for both folders, `legacy_backup_generations_past_the_cap_are_listed_too`, and the junk walk skipping both folders.

Status: done 2026-10-20
Model: Claude Code / claude-opus-5-5
