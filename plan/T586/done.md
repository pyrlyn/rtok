Confirmed with separate Git repositories: [project_key_with](src/project.rs#L42) passes the origin string into [canonical_url](src/project.rs#L120) without its checkout context. Two checkouts at a/checkout and b/checkout, each configured with origin ../remote.git, target different bare repositories but both receive the key ../remote. Git ls-remote controls returned different fixture refs, while the actual project resolver returned equal keys. Replacing A's relative URL with the absolute path of the same remote also changed its key. [Task projects](src/tasks/run.rs#L56) use this key for counters, and [claim injection](src/hooks/mod.rs#L162) uses it to select project claims. Unrelated repositories can therefore share a namespace, while alternate spellings of one local remote split it. T441.3's canonical origin identity has no relative-path exception or existing fix for this case.

Execution plan:

1. Add two-checkout fixtures with identical relative origin strings resolving to different repositories; assert distinct keys. Add relative/absolute/file-URL spellings of the same local remote and assert one identity, using real Git resolution as a control.
2. Distinguish local filesystem remotes from network URL and scp forms before normalization. Resolve relative local paths with the proper checkout context, canonicalize existing destinations and provide a stable absolute fallback for missing ones. Preserve filesystem case semantics and validate linked-worktree behavior against the existing shared-project contract.
3. Cover nested cwd, dot segments, symlinks, spaces and supported Windows path forms without regressing network remote normalization. Verify task-counter and claim lookups remain isolated between different repositories.
4. Handle persisted old keys explicitly: seed/reconcile counters from the intended project's adapter as needed, and avoid blindly migrating an ambiguous shared relative key into every project. Test compatibility without silently resetting IDs or moving unrelated claim rows.

Check: different resolved local remotes never collide solely because their relative strings match, equivalent local spellings share a key, existing state is handled deliberately, and `just check` passes. The isolated harness compiled actual project resolver/normalization functions with a standard filesystem adapter; Git controls used only scratch local repositories. Store counter/claim behavior was source-traced, not exercised end to end.

Result: Filesystem remotes (relative, absolute, `file://`, `file://localhost/`) resolve from the main checkout: canonicalised when present, lexically normalised otherwise, `.git` and trailing separators stripped. Local paths keep their case. Old keys are not migrated; `Project::create` already raises a new key's counter past the adapter's ids.

Check: `one_relative_origin_in_two_checkouts_is_two_keys`, `spellings_of_one_local_remote_share_a_key` (6), `network_remotes_are_not_resolved_against_the_checkout` (3), `a_missing_local_remote_keeps_its_spelled_case`. Windows path forms untested.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
