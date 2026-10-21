Source audit only; no product code changed. Traced host hook identity and parent discovery through `Store::register_agent`, registry queries, and `model::agents` tree/session selection, comparing ordering and repeated-registration behavior with existing agent lifecycle coverage.

Execution plan:

1. Trace parent/session identity discovery, registry upserts, query ordering and the `model::agents` nesting/session-selection path. Inventory the existing lifecycle tests and the invariants they establish.
2. Use an isolated real-store fixture to compare child-before-parent registration and re-registration with normal parent-first registration; include the model projection and controls for multiple sessions and ordinary orphan agents.
3. Deduplicate exact scenarios against plan, done, roadmap and ideas, especially T282, T284, T324, T262.5 and the T540/T610 audits. File only a reproduced, contract-relevant defect; otherwise record the investigated boundaries and evidence limits.
4. Keep the pass documentation-only. Verify task-table/todo parity, source links and `git diff --check`, then run scoped `just check` against the reviewed base.

Result: filed T613 for a sub-agent registered before its main agent. The actual store leaves the child row's `parent_id` null after the main row appears, and repeating the child registration does not repair it. The model then selects the older orphaned child as the session's main agent. T282's relevant test is main-first; T284 covers ordinary nesting; T324 covers repeat-registration revival, not late-parent repair. No cycle or nested-grandchild issue was filed because normal registry flow only assigns direct main-agent parents. Task ID allocation/validation inspection found no additional finding.

Evidence revision: `2d91199fdb507af71eedbcf1b7ed127a06b39784`; fetched main was `085b49e9450de9326e7738dc441718e1bd667363`. The offline scratch executable linked the actual `rtok-store` crate and used its in-memory migrations/public methods, imported actual agent-view types, and compiled the production pure model functions. Child-first registration, late main registration, repeated child registration, and main-first/nesting/repeat controls ran. The child-first case retained the same child ID but `parent_id` remained null, the main had zero children, and `session_views` selected the older child root. A 1.2-second ordering gap made the query order deterministic. Synthetic session totals were used; no full CLI, host integration, real agent, or concurrent process was exercised. No raw SQL or mocked store behavior was used.

Validation: the in-memory Store/model probe passed its child-first reproduction and parent-first/nesting/repeat controls. `git diff --check` passed, and the scoped `just check` against `085b49e9` reported no code changes and skipped Cargo tests for this documentation-only diff. Active plan cards and `todo.md` contain the same 121 unique task IDs.

Status: done 2026-10-11
Model: OpenAI / GPT-6
