Source-confirmed: [sync read](src/plugins/memory/sync.rs#L119) uses read_to_string(...).unwrap_or_default(), treating every read error as an empty file. An existing CLAUDE.md containing an invalid UTF-8 byte therefore bypasses the managed-block guard; a normal sync prepares only the new memory block and [backs up and overwrites](src/plugins/memory/sync.rs#L138) the original, losing all outside content from the active host file. A backup does not make this replacement correct. A missing file is the only read failure that should enter the create path.

Execution plan:

1. Add a scratch host-file fixture with ordinary outside text and an invalid UTF-8 byte; assert sync fails and preserves the original bytes and stored digest.
2. Handle NotFound explicitly as a new file; propagate all other read failures before rendering, backups, writes or digest updates.
3. Cover unreadable files where permissions allow a reliable fixture, dry-run/remove/force variants, and successful creation of a genuinely missing file.

Check: failed reads never replace or remove host content or change sync metadata; existing valid managed-block behavior and `just check` pass. The isolated read/splice probe establishes the lossy replacement text; the live CLI write regression remains part of this fix.

Result: `run()` in `src/plugins/memory/sync.rs` reads the host file through `fs_kit::read::text_or_empty`, which treats only NotFound as empty and returns every other error with the path before rendering, backups, writes or digest updates. Dry-run, remove and force share the same read.

Check: `a_non_utf8_host_file_aborts_sync_without_touching_anything`, `a_non_utf8_host_file_keeps_an_existing_digest`, `an_unreadable_host_file_aborts_sync_without_touching_anything` (unix, skips as root) failed before and pass after; `a_missing_host_file_is_still_created`; `plugins::memory` (94) and trycmd green.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
