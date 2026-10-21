Bounded source audit only; no product code changed. Read the task-claim, message, pending-worktree, worktree-claim, hook-field, retention, and path store methods and their relevant callers, focusing on ordering, identity and cleanup. This was a targeted pass, not an exhaustive audit of the store crate.

Execution plan:

1. Inspect unreviewed store queries and their callers, tracing retention cutoffs, identity keys, ordering, and reference cleanup across the small store/foundation modules.
2. Execute focused real-source reproductions and controls for any concrete boundary case, using isolated fixtures and the actual store where practical.
3. Deduplicate exact scenarios against `plan.md`, `done.md`, `roadmap.md`, and `ideas.md`; record only confirmed findings and distinguish source inspection from executed paths.
4. Record the reviewed scope and evidence limits, verify the documentation diff, run scoped `just check`, and prepare the PR #986 update.

Result: filed T615 for `latest_task_claim` returning the lexically earlier task when multiple claims have the same second-resolution timestamp. The task-claim source test claims R1, R2, then R1 again, so its expected R1 also matches the tie-break and does not establish which equal-second claim is latest. The old T442 task-claim card defines the SessionStart/PostCompact row and claim lifecycle but does not specify or test equal-second ordering. The unrelated T442 proxy-hop card is a separate task with the same ID. No additional retention, message, pending-worktree, worktree-claim, hook-field, or path finding was confirmed in this bounded review.

Evidence revision: `e8d36a0c6fdfb27f37ca97ceb33e6230bee545d1`; fetched main was `085b49e9450de9326e7738dc441718e1bd667363`. Source inspection covered `task_claims.rs`, `messages.rs`, `worktree_pending.rs`, `worktree_claims.rs`, `hook_fields.rs`, `retention.rs`, `paths.rs`, plus task runner and hook callers. The offline scratch executable used the actual `rtok-store` crate, real in-memory migrations and public APIs. Two task claims and a re-claim in one wall-clock second returned the lexically earlier task and its old title; separate-second ordering, release fallback, and project/agent isolation controls passed. Full hook dispatch, live hosts, concurrent processes, and Windows path behavior were not exercised; the injected-text effect was source-traced only. No raw SQL or mocked Store behavior was used.

Validation: the actual Store probe passed the same-second reproduction and separate-second, release-fallback, project and agent controls. `git diff --check` passed, and scoped `just check` against `085b49e9` reported no code changes and skipped Cargo tests for this documentation-only diff. Active plan cards and `todo.md` contain the same 122 unique task IDs.

Status: done 2026-10-11
Model: OpenAI / GPT-6
