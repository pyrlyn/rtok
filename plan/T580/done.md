Confirmed with the actual [AI render loop](src/report/ai.rs#L41) and [token estimator](src/tokens.rs#L22): admission subtracts only section-body estimates, then appends the report header, date range/store path, dropped-section list and section anchors outside the budget. Eight controlled bodies admitted at an 808-token budget produced a final 840-token estimate. A longer store path adds more unbudgeted output. T22.4 and report.budget_tokens describe a cap on the AI report; existing tight-budget tests check whole-section omission but never estimate the final output. T565 covers a similar issue in mem_pack, not this renderer.

Execution plan:

1. Add final-output estimator assertions using real Document fixtures at a tight budget and exact admission boundaries, including long store paths, all-kept and partially dropped sections.
2. Budget the complete serialized report, including anchors and the actual dropped list. Recompute admission when dropping a section changes metadata; preserve whole sections, their order and byte-stable output.
3. Define explicit behavior when even the minimal metadata does not fit, and cover zero/tiny budgets, non-default estimator rates, Unicode and both TOON and fallback table rendering. Do not silently emit output above the configured cap.

Check: every successful AI report stays within report.budget_tokens according to the configured estimator, while tables remain whole and drops are accurately named; `just check` passes. The isolated probe used actual render/admission and estimator functions with controlled section bodies and minimal model/config stubs, not the full document renderer.

Result: `render` in `src/report/ai.rs` budgets the whole serialized text (header, store path, `dropped:` line, anchors). When even the header does not fit, output degrades to the title plus `dropped:`, then to empty.

Check: `whole_output_stays_within_the_budget` (budgets 0..1500, two estimator rates, long and Unicode store paths), `budget_boundary_is_exact`, `a_budget_below_the_header_degrades_explicitly`; all failed against the old `render`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
