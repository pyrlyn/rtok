Review `src/project/refs.rs` and its language-specific resolvers; `src/plugins/memory/{pack,sync,files}.rs`; and the CLI entrypoints that accept or emit project, reference, note, and file data. Read `src/plugins/memory/AGENTS.md` and its `PLAN.md` first. T538/T539 already cover import hash reservation and revise project scope; do not repeat the previously reviewed save/import/revise paths. This is a source audit and docs-only claim: do not change product code or copy real user/host data.

Execution plan:

1. Trace reference discovery and resolution across Cargo, Go, npm and Python, including relative paths, project roots, malformed inputs and missing-file behavior.
2. Trace `memory pack`, sync and note-file linking through their CLI/MCP/store boundaries; check project scoping, path normalization, size/budget limits, stale state and error/fail-open behavior.
3. Review CLI parsing and serialization for these data paths, compare each concrete candidate against active, roadmap, done and idea entries, then file only distinct source-confirmed fixes with reproduction, implementation steps and checks; sync `todo.md`.

Check: every filed issue has a source-backed trigger and deterministic fixture; no duplicate is added; no product files or real host data are changed; plan/todo stay synchronized; `git diff --check` and scoped `just check` pass.

Result: reviewed Cargo/npm/pnpm/Go/Python reference resolvers, graph-follow consumption, memory packing and its MCP boundary, memory sync and its CLI/SDK write path, and note-file mention normalization. Filed six distinct defects T562–T567 with ordered fixes and acceptance checks. Compared related T69.6/T460/T374/T452/T329.7 and active/roadmap/idea entries. A recursive-glob fixture also reproduced the one-level limitation already acknowledged by completed T329.7; it was deliberately not filed as a new finding. No product code, dependencies or ideas were changed.

Evidence revision: `c1f6ac06b5891f592f7da29449be3877a5d38305` (product source unchanged from `0b14b88e`). An offline Rust harness compiled the actual pack module and reference resolvers, plus verbatim sync and path helpers. It reproduced the basename digest collision, marker-title round-trip failure, a budget-2 pack costing 4 tokens after rendering, hidden/dot-relative path loss and wrong-sibling association, and external references contributed by an excluded pnpm member. An invalid-UTF-8 read/splice fixture demonstrated preparation of replacement text without the original outside bytes; inspection of SDK backup/write established that existing file UTF-8 is not validated there. No live CLI overwrite, store integration, real agent or graph-registration run was performed; those regressions belong to the fix cards. Initial harness compilation failed on module resolution and a scratch SHA formatter; after fixing only that harness plumbing, all seven probes passed.

Validation: `CHECK_BASE=0b14b88eb56469625eebc7e1df92ae1b77bcf2b2 mise exec -- just check` passed for the documentation-only diff (no repository Cargo tests selected); `git diff --check` passed. New source links resolve; the active table and todo have matching unique identities. This pass extends audit coverage without claiming exhaustive verification of every module or platform.

Status: done 2026-10-11
Model: OpenAI / GPT-6

Left over from T500. `CallsStore::folded_counters(drawn, more, scope, now)` and the pane's "folded calls" lines (`{label} · {n} folded`) exist, but the TUI has no one-project drill view, so nothing fills `LiveCalls.drawn`/`more` and the counter never shows. Give the TUI a one-project view that supplies the drawn nodes and `more`, then wire it in and drop the `#[cfg(test)]` `set_drawn`.

Check: in the one-project view the pane lists folded targets on their nearest drawn ancestor, else on the "+N more" group; TUI test; `tests/surface_parity.rs`; `just check`.

Execution plan (2026-10-12): 1. a TUI one-project drill view on the graph/calls page that, like the web drill view, fills `LiveCalls.drawn` and `more`; 2. wire `CallsStore::folded_counters` into the pane and drop the `#[cfg(test)]` `set_drawn`; 3. TUI test (folded targets on the nearest drawn ancestor, else on "+N more"), `tests/surface_parity.rs`; `just check`.

Result: `d` on the TUI Graph page opens a one-project view on the registry row under the cursor (`d` again closes it, `d` on another row re-drills). Its nodes come from `plugins::graph::drill::run`, the web page's call, at the same default cap of 500; the folded-target count stays `CallsStore::folded_counters` and the live pane shows `<label> · <n> folded` on the nearest drawn node, else on `+N more`. The drilled project is the scope for the outside-scope mark, as `scope_names(rows, drilled)` is on the web page. Drill state lives in `src/tui/drill.rs` and loads on a background thread; the `#[cfg(test)]` `set_drawn` is gone. The drilled view loads once per `d`; after a re-index press `d` twice to reload.

Check: Fail-first `the_one_project_view_counts_folded_targets_on_the_nearest_drawn_node_or_the_group` (510-file indexed project, cap leaves 10; failed with `Drill::apply` not storing nodes); `tests/surface_parity.rs` `live_extras_are_on_the_page_and_in_the_tui` asserts both surfaces call `drill::run(` and the KEYS table documents `d`; `just full-check`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
