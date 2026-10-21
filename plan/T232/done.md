Found 2026-09-23 in the D27 audit: `worktree list` and `worktree gc --dry-run` are exempt as "reads git/filesystem, not the store"; still the only view of owner locks, age and `target/` disk cost is the CLI.

Plan: page `("worktrees", "worktrees")` — path, branch, owner (lock reason), age, `target/` size, prunable flag: the same rows as `worktree list --json`, read through one accessor; `gc`/`clean` stay CLI. Bound the filesystem walk (cached size, TTL) so the snapshot tick stays cheap (T206).

Check: `worktrees_page_exists_on_both_surfaces`; a fixture repo with one locked worktree renders its owner; `just check` green.

Result: New Worktrees page ("worktrees","worktrees") on both surfaces: model::worktrees_page_text renders worktree::list::rows through its own to_table (path, branch, owner, state, age, target/ size), read-only; gc/clean stay CLI. The walk takes tens of seconds in a built checkout, so the page reads through a new Background<T> cache (a background thread refreshes, the tick never blocks) with a 300 s WORKTREES_TTL; the Hosts page now uses the same helper instead of its own copy. worktree list moved from EXEMPT to COMMAND_PAGES. Tests: worktrees_page_exists_on_both_surfaces, worktrees_page_shows_a_locked_worktree_s_owner, webui snapshot parse and missing_worktrees_is_a_failed_tick_not_empty.

Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
