Source audit only; no product code changed. Reviewed the store sanitizer, byte/JSON dispatch, character and wrapper ordering, stored-call and split-hook consumers, plus raw-storage bypass and existing T431/T432/T433 contracts. This sampled pass does not prove every terminal grammar or storage integration correct.

Execution plan:

1. Trace stored-body/text sanitation and its consumers, comparing wrapper removal, terminal escapes, Unicode boundaries and raw-storage bypass with T431/T432 and existing tests.
2. Check combined transformations, repeated sanitation and unchanged-input byte preservation with actual sanitizer functions in isolated fixtures.
3. Deduplicate failures against existing storage/sanitizer tasks, file only confirmed cases with ordered fix steps, and document untested integrations.
4. Complete the audit record, verify plan/todo parity and the scoped documentation gate, then update PR #986.

Result: filed T599 for non-idempotent cleaning when ANSI/zero-width removal forms a wrapper after the wrapper-removal stage. This extends T431's existing invariant with a deterministic composition counterexample; T432's intentional wrapper retention remains out of scope. No broader data-loss or privacy claim was established.

Evidence revision: `22d0a572cb91063ee770c161e1fa30ec4751e545`; main remained `ee8d66288b1d1b850ba24b7f9a9a70481695c3ca`. An offline scratch executable imported the actual sanitizer module by path. With ANSI reset or U+200B inside an opening wrapper name, the first text pass retained the newly formed wrapper and the second removed it; both JSON body fixtures also changed on a second pass. Plain-wrapper and clean-JSON controls were stable, and the JSON numeric field remained unchanged. No real records, database, network or host application were accessed; persistence, raw bypass and full proxy behavior were source-traced, not executed.

Validation: `CHECK_BASE=ee8d66288b1d1b850ba24b7f9a9a70481695c3ca mise exec -- just check` reported no code changes against the merged-main baseline and skipped Cargo tests for this documentation-only diff; `git diff --check` passed. The active task cards in plan.md and rows in todo.md contain the same 117 unique IDs; the active numeric summary table separately tracks its 87 rows.

Status: done 2026-10-11
Model: OpenAI / GPT-6
