First subtask of T441 (task adapters). Re-check the prior-art table in the T441 card against primary sources and settle what research can of its open questions: GitLab work items API vs issues, GitHub sub-issue limits, rate limits, the allocator's storage.

Check: findings with sources and dates in `research.md`; the T441 card adjusted to them.

Result: `research.md` §35. Corrections to the card: Backlog.md (v1.53.0) and Taskmaster (0.43.1) lock id allocation per repository, beads (v1.3.1) uses hash ids and already syncs GitHub and GitLab, so "nobody has collision-free ids" was wrong; what rtok would add is one per-project counter shared by every checkout, worktree and host on the machine. Settled: the allocator is a Diesel table in the existing store (WAL + `exclusive_transaction`); subtask depth 2 fits GitHub sub-issues (100 per parent) and GitLab issue → task (every tier, parent link through GraphQL only); GitLab native Status and scoped labels are Premium, so Free swaps a plain `status::` label; won't-do is native on both; GitHub's 500 content-creating requests/hour paces bulk writes; remote collision checks list by label, not search. New open question for the creator in §12: build rtok's own core or adopt beads/Backlog.md.

Status: done 2026-10-07
Model: Claude Code / claude-opus-5-5
