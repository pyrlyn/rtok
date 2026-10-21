Source audit only; no product code changed. Reviewed Project create/status/claim/release and dependency handling, the shared adapter contract, disk persistence and claim locking against T441/T442 and prior disk findings. This sampled pass does not claim exhaustive allocator, remote adapter or concurrency coverage.

Execution plan:

1. Trace Project create/status/claim/release through adapter locks, task persistence and claim rows; compare T441/T442 contracts and earlier disk-task findings.
2. Examine lock ownership, stale recovery and guard release under delayed writers. Separate documented remote last-write-wins behavior from local mutual-exclusion promises.
3. Reproduce uncovered local lock cases using actual functions and scratch directories, with no real agents, tracker writes or shared task files.
4. File confirmed defects with ordered fixes, document evidence limits, verify task-card/todo parity and scoped checks, and update PR #986.

Result: filed T601 for an expired guard unlinking its successor's fresh lock and letting a third holder enter. T442's documented timeout takeover is setup for this defect, not a separate finding. Its existing stale-file test has no displaced live guard; T541's task identity mismatch remains distinct. No additional lifecycle hypothesis was confirmed.

Evidence revision: `0e19cd1733d6f4fdc4b800bc860d638c63878644`; main remained `ee8d66288b1d1b850ba24b7f9a9a70481695c3ca`. A scratch executable compiled actual DiskGuard, lock_claim and lock_is_stale functions. After an actual 11-second wait B acquired while A's guard remained alive; dropping A removed B's fresh file and C acquired while B's guard remained alive. A fresh-lock contender was refused, and normal release allowed reacquisition. These were real scratch files with guards scheduled in one process, without a mocked clock. Full task mutations, multiple OS processes, real agents and remote trackers were not exercised.

Validation: `CHECK_BASE=ee8d66288b1d1b850ba24b7f9a9a70481695c3ca mise exec -- just check` reported no code changes against the merged-main baseline and skipped Cargo tests for this documentation-only diff; `git diff --check` passed. The 118 unique active plan cards and 118 todo rows have equal ID sets, with no duplicates.

Status: done 2026-10-11
Model: OpenAI / GPT-6
