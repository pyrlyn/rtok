Confirmed with actual SDK functions: [backup_at](crates/rtok-agent-sdk/src/lib.rs#L181) skips a copy if any file in the shared _backup directory has identical bytes, regardless of basename. [prune_backups](crates/rtok-agent-sdk/src/lib.rs#L103) subsequently prunes generations of one basename without knowing other files depend on that copy. Create A.json and B.json with the same original text; with backup_files=1, write A1 to A, B1 to B, then A2 to A. B's backup was skipped, and the final A write removes the only copy of B's original bytes. T72 intentionally introduced basename-agnostic dedup and T249 per-basename pruning, but neither tracks this interaction.

Execution plan:

1. Add the two-file/three-write fixture using SDK write with backup enabled and a retention cap of 1; assert both files retain their required undo content.
2. Make deduplication and retention share an ownership model. Prefer independent per-basename backup identity unless cross-file ownership is explicitly tracked; never treat a foreign file's prunable copy as sufficient protection.
3. Cover repeated same-content writes, different basenames, same-second suffixes, lowered caps and cleanup via stale_backups; preserve intentional no-op behavior without losing another file's recovery bytes.

Check: pruning A cannot delete B's only required undo content; repeated installs remain idempotent, the retention cap remains well-defined per file, and `just check` passes. The isolated fixture exercised real SDK backup/write/prune functions against scratch files.

Result: `backup_at` treats a backup as a duplicate only if it is a generation of the same file name, so one file's cap cannot prune another file's only undo copy.

Check: `same_original_bytes_in_two_files_keep_one_undo_copy_each`; the old cross-name test is now `backup_skips_when_an_identical_generation_of_that_name_exists`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
