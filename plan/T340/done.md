In the plan, T330 "Never touched" (branch `docs/plan-agents-junk`, ~line 701, from PR #541 (T330), not merged yet) lists "`rtok.db`" (as T182 did: "It never touches `rtok.db`", done.md:6879). The same task clears "rtok's own session rows and logs keyed by session id (T284)" (`sessions` kind, ~line 730) and "Graph/tags index rows ... for projects no longer in the registry" (`index` kind, ~line 710); T182's result says the graph index "live[s] in the DB" (D18, line 695). These contradict each other because both row kinds live in `rtok.db`, so T330 cannot clear them without touching it.

Goal: research both approaches, compare trade-offs, recommend one, then update the conflicting tasks. Do not change either task before the decision.

Check: the recommendation and the chosen approach are recorded in this card (or as a decision row), and every task named above is updated so the plan no longer contradicts itself.

Decision (2026-10-09, creator): reworded to match the shipped code (research option C). The `rtok.db` file and its `-wal`/`-shm` files are never deleted, moved or emptied (`protected()` in `src/agents/junk_clear.rs` already enforces it). Rows change only through the store's retention: `Store::run_retention`, which `agents junk clear` already runs, and `Store::remove_project`. Junk adds no new row deletion; rtok's own agent and session rows stay out of T330 (the logs keyed by session id already age out with their calls). The graph/tags index rows of a gone root or a removed project are already delivered by retention and `remove_project`, so the `rtok.db` part of T330.5.3 is delivered. Rows of a root that exists but is not registered are not handled; how often that happens is unmeasured, so it would be a separate measured task.

Updated in `plan.md`: T330 "Never touched", the cache bullet for index rows, the `sessions` row, and the T330.5.3 card (title, scope, question 2 removed). Stale reference fixed: T182's "never touches `rtok.db`" is in this file, not at the old `done.md:6879`. The optional D36 note was not added.

Status: done 2026-10-09
Model: Claude Code / sonnet-5.5
