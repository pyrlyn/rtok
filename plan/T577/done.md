Confirmed with an isolated directory fixture: [recheck](src/agents/junk_clear.rs#L278) compares PACKAGE_LOCKS only with the candidate's own basename. Its protected-path list contains the store and host settings paths, not discovered descendant lockfiles. An eligible explicitly configured cache containing nested/Cargo.lock passes recheck, then [remove](src/agents/junk_clear.rs#L334) recursively discards its children and deletes that lockfile. The cache root remains, while the same Cargo.lock checked directly is refused. [extra_items](src/agents/junk_review.rs#L169) accepts the configured cache root; the plan's D36/age/running gates do not add a descendant-lockfile check. This contradicts completed T330.4's promise to refuse a path that is or holds a package-manager lockfile; T330.5.1's explicit exclude globs do not supply the missing automatic protection.

Execution plan:

1. Add scratch-tree cleanup regressions for an eligible extra cache with direct-child and deeper Cargo.lock files. Assert the parent is refused and all contents remain; retain controls for a direct lockfile, a cache without protected descendants, settings/store descendants and CACHEDIR.TAG.
2. Share a bounded descendant-protection check between planning and the immediate pre-delete recheck. Match the existing PACKAGE_LOCKS set, never follow symlinks, and refuse deletion when inspection fails or times out. Protect the entire removal candidate if it contains a protected lockfile.
3. Cover tagged build/cache roots, extra cache roots and recursively removed temp entries, a lockfile appearing after preview, all supported package lock names, unreadable subtrees and trash/permanent-delete paths. Preserve existing store/settings and exclude protections and clear refusal reasons.

Check: no planned recursive deletion or cache emptying removes a protected descendant lockfile, unknown inspection results remain non-destructive, and `just check` passes. The actual recheck/newest/usage-walk/remove functions and permanent-delete branch reproduced the loss; a synthetic clock made the fixture older than the settle window. This was not a full report/CLI or trash integration test.

Result: A shared `holds_lockfile` walk (no link following, `PACKAGE_LOCKS` names, refuses on read error or timeout) protects any directory holding a nested lockfile in both `plan` and `recheck`; stale-worktree and archive kinds are skipped.

Check: `a_folder_holding_a_nested_lockfile_is_neither_planned_nor_emptied`, `the_lockfile_walk_ignores_links_and_refuses_what_it_cannot_read`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
