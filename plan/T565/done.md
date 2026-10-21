Source-confirmed: [pack_notes](src/plugins/memory/pack.rs#L104) budgets tier text and a fixed separator cost, but [render](src/plugins/memory/pack.rs#L256) adds an id/tier header and separators afterwards. [mem_pack](src/plugins/memory/mod.rs#L461) returns that larger string without enforcing max_tokens again, contrary to the memory plugin invariant. With the four-characters-per-token estimator, hit (1, "a", "b"), no body and max_tokens=2, the pack reports 2 used tokens while the rendered text "1\tabstract\n1 a\nb" costs 4. T460 covers tier selection, not final serialization overhead.

Execution plan:

1. Add the minimal budget-2 fixture and multi-note cases asserting the estimate of the complete returned string stays within the requested effective budget.
2. Budget the same serialized representation that will be returned, including headers, newlines and separators, when admitting or deepening entries.
3. Derive used_tokens from the final representation; cover all tiers, Unicode, estimator settings and budgets too small for even one complete entry.

Check: estimate(render(pack)) <= max_tokens for every fixture, used_tokens agrees with the returned payload, note entries remain complete, and `just check` passes.

Result: `mem_pack` measures each serialized entry (id and tier header included), re-measures the final rendering and drops trailing entries until it fits `max_tokens`; `used_tokens` equals the returned payload's estimate.

Check: `the_returned_text_never_exceeds_max_tokens`, `a_budget_too_small_for_one_complete_entry_returns_nothing`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
