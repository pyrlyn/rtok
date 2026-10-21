Part of T330.5. Split from T330.5.1 on 2026-10-08 to keep that task under the 500-line cap; depends on T330.5.1 (#827). `backups` (review) over the T330.5.1 report, plan, re-check and apply: rtok's own `_backup/<name>.bak-<ts>` generations in the folders of a host's config files that `setup.backup_files` has no room for (a cap lowered after the copies were taken; the newest copy of a file always stays), with a read-only `rtok-agent-sdk::stale_backups` twin of `prune_backups`; other `*.bak`, `*.bak-<ts>` and `*~` files under an agent's folders are listed read-only (no D36 evidence), and rtok's own `_backup` folder is never walked for them. `backups` joins the `--kind` values.

Check: a `_backup` past the cap is planned and the newest kept; a `notes.txt~` is listed, never planned; the `--kind` completions and help goldens list `backups`; `just check`.

Result: `rtok_agent_sdk::stale_backups` (per base name all but the newest `keep`, oldest first; `0` keeps all; only a `_backup` folder), `junk_review::backup_items` (class `review`, evidence `RTOK_OWN`) fed from `junk::marker_dirs` (extracted from `host_folders`), and `*.bak`, `*.bak-<ts>`, `*~` files found by `junk_kinds::found_items` (`is_backup`, class `review`, "not documented", `_backup` skipped). `"backups"` is in both `KINDS`; completions goldens (bash, zsh, clink) and `help-subcommands` updated; `junk_clear` test covers a running host not holding back rtok's own copy. No new dependency; docs list no junk kinds, so none changed.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
