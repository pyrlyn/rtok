Review previously unexamined plugin documentation ingestion/query paths, privacy and redaction boundaries, and snapshot/checkpoint/retention behavior. Compare each source-backed candidate with active, roadmap, done and idea cards before proposing a fix; preserve the shared main checkout's dirty T522–T527 changes. This task is an audit and documentation claim only.

Execution plan:

1. Read the relevant `src/plugins/<id>/AGENTS.md`, architecture, research and existing task cards before tracing each path.
2. Inspect documentation ingest and retrieval identity/scope, measurement accounting, checkpoint project identity, redaction, and retention invalidation; use isolated fixtures or source-level probes where practical.
3. Deduplicate candidates against plan, roadmap, done and ideas; file only distinct, source-confirmed findings as sequential tasks with triggers, steps and checks. Do not change product code.

Check: findings have source references and concrete reproductions; no duplicate task is added; `plan.md` and `todo.md` remain synchronized; `git diff --check` and `just check` pass.

Result: reviewed docs-plugin lock resolution, fetch/index/query/get and local-document ingestion, the associated store/schema and query accounting, checkpoint extraction/prefilter/render/store/restore, and project identity for session handoff. Filed T548–T553: private-text persistence, cross-workspace local docs, invalidated ids on unchanged ingestion, inconsistent token-accounting baseline, checkpoint prefilter false negatives, and the handoff project-resolver bypass. Compared exact triggers with T455, T417, T133, T154, T71.2 and I-105; their broader feature/privacy proposals do not track these failures. This pass did not claim exhaustive archive, retention, host or platform coverage.

Evidence revision: `99b6753d44f8da9a8fc0dc9e22113e520cb89d7d`; product source remains unchanged from `0b14b88e`. An offline Rust harness compiled verbatim checkpoint extraction/rendering code with serde_json and the repository's shlex version, omitting only the unused SDK import. It reproduced spaced-JSON and mixed-content prompt loss by comparing filtered/unfiltered extraction, and retained a private-tagged sentinel in rendered output. The initial harness lacked shlex and failed to compile; after adding that scratch-only dependency all three probes passed. Storage, docs and project-identity effects are source-confirmed, with integration regressions assigned to each fix card. No live agent, network fetch or product code edit was involved.

Validation: `CHECK_BASE=0b14b88eb56469625eebc7e1df92ae1b77bcf2b2 mise exec -- just check` passed for the documentation-only diff (no Cargo tests selected), as did `git diff --check`. New source links resolve and the active table/todo ids were checked for equality and duplicates. No new ideas or product dependencies were added.

Status: done 2026-10-11
Model: OpenAI / GPT-6
