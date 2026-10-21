Found 2026-10-20 with T949 and T950: 13 worktrees of this repo held ~134 GB, nearly all `target/`, and a `worktree gc` dry run removed one. `newest_until` folded the mtimes of files under tagged cache roots into the worktree's newest mtime, so a cargo or rust-analyzer build reset gc's 24 h idle clock without anyone touching the work.

`newest_until` now skips every tagged cache root (`walk`'s `sources_only`), so gc's idle age reads source files only. `usage`, `worktree list` and `worktree clean`'s per-cache ages still count the caches.

Check: `newest_until_leaves_tagged_caches_out` — an old source file and a new `target/` give the source's mtime, while `usage` still reports the cache's for the worktree and for the cache root.

Status: done 2026-10-20
Model: Claude Code / claude-opus-5-5
