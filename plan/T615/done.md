Confirmed on `e8d36a0c6fdfb27f37ca97ceb33e6230bee545d1` with the actual in-memory Store. [upsert_task_claim](crates/rtok-store/src/task_claims.rs#L36) records `since` in whole Unix seconds, while [latest_task_claim](crates/rtok-store/src/task_claims.rs#L78) sorts by `since DESC, task_id ASC`. Claim `R1` and then `R2` for the same project/agent within one second: the method returns `R1` and its old title. Re-claiming `R2` within that second still returns `R1`. The existing test claims `R1`, `R2`, then `R1` again, so its expected result coincides with the lexical tie-break and misses the defect.

The task runner writes this row after a successful claim and on a same-agent re-claim ([claim_held](src/tasks/run.rs#L245)); it does not restrict an agent to one active task. [claim_injection](src/hooks/mod.rs#L159) uses the result directly for the SessionStart/PostCompact task line, despite its explicit newest-row contract. Fast successive claims can therefore inject the earlier task instead of the task most recently selected. This is separate from T601's disk lock ownership race and T592's OTel cursor ordering.

Execution plan:

1. Add deterministic store regressions for equal-second `R1` then `R2`, the reverse order, and re-claiming either existing row. Assert last successful claim order, not lexical ID order; keep separate-second, project/agent isolation and release-fallback controls.
2. Persist an ordering value that advances on every successful claim and re-claim, including updates of existing rows. Use typed Diesel operations and a transaction-safe ordering scheme; retain `since` for elapsed-time/display semantics. Include a compatible migration and deterministic handling of pre-existing ties.
3. Cover the runner-to-hook path so SessionStart/PostCompact select the latest claimed task and its current first-line title, including rapid re-claims. Preserve release/finish cleanup and the injection budget.
4. Run focused task-claim, runner and hook tests and `just check`.

Check: selecting the latest claim is independent of task ID spelling when timestamps tie. The isolated probe used real Store migrations and public APIs, checked that all reproducing writes occurred in one wall-clock second, and exercised re-claim, release fallback, project/agent isolation and separate-second controls. No raw SQL, full hook dispatch, live host or concurrent process was exercised; the injected-text effect was source-traced.

Result: Claims are ordered by a monotonic `seq` (migration `0047_task_claim_seq`, old claims numbered in their old order) instead of the whole-second `since`.

Check: `claims_in_one_second_resolve_by_claim_order`, `migration_0047_numbers_old_claims_in_their_old_order`, `session_start_names_the_latest_claim_made_within_one_second`, `the_stored_claim_follows_the_last_claim_made`; schema snapshot updated.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
