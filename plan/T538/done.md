Source-confirmed on `0b14b88e`: [`memory::import::run`](src/plugins/memory/import.rs#L94) inserts a body's hash into `seen` before checking whether the topic key is already occupied. Seed local topic A with body `old`; import A with body `new`, then free topic B with body `new`. A is correctly skipped to protect the local note, but B is also skipped because A already reserved `new` in memory, although no stored note has that body. The reported result is zero insertions; reversing the input order permits B. The same false skip affects dry-run.

Execution plan:

1. Add this two-line fixture in both orders, with an existing local A, for dry-run and actual import.
2. Check body/topic eligibility before reserving either; reserve both only for an accepted simulated or successful insertion. Handle the concurrent insert returning `None` without poisoning later rows.
3. Preserve idempotency, the local-topic-wins rule and intentional dedup against bodies that really exist.

Check: B is imported regardless of the skipped A's position, A keeps `old`, dry-run matches actual import, a second import is idempotent, and `just check` passes.

Result: `src/plugins/memory/import.rs` checks the topic key and body hash first and reserves both only once a line is accepted, in dry run and real import alike.

Check: `a_skipped_topic_does_not_reserve_its_body_for_a_later_free_topic` (both input orders, dry run and real, second import inserts nothing) failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
