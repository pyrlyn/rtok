In the plan, T330 (branch `docs/plan-agents-junk`, ~line 689, from PR #541 (T330), not merged yet) makes `build` (`target/`, `dist/`, ...) in agent worktrees a `safe` kind cleared by default with no age rule, skips only "`temp`, `locks`, `swap`, `index`" for a running agent (~line 796), and clears caches by "keeping the top folder ... and keeping any `CACHEDIR.TAG`". Done task T152 (done.md:5229-5233) clears the same tagged caches only when idle ("`--idle`", default 24h), "Never the cache of the worktree the command runs from unless its path is given explicitly", and deletes "one cache root at a time with `remove_dir_all`". These contradict each other because two commands would delete the same `target/` directories under incompatible safety rules: T330 would clear a live agent's fresh build cache that T152 deliberately keeps.

Goal: research both approaches, compare trade-offs, recommend one, then update the conflicting tasks. Do not change either task before the decision.

Check: the recommendation and the chosen approach are recorded in this card (or as a decision row), and every task named above is updated so the plan no longer contradicts itself.

Recommendation: T152's rules. Two commands deleting the same `target/` must not disagree, and T152's rules exist because a fresh build cache under a live agent is in use: clearing it mid-build breaks that build and costs a full rebuild. Idle-only (default 24 h), never the caller's own worktree unless its path is given, and one cache root at a time keep `agents junk clear` as safe as `worktree clean`.

Decision (creator, 2026-10-06): `rtok agents junk clear` clears `build` and `CACHEDIR.TAG` dirs only under T152's rules. The T330 kinds table (`build` row), the T330 "Cleared" list, the running-agent rule and the T330.3 card now say so.

Check result (2026-10-06, Claude Code / opus-5.5): decision recorded here; T330 (`build` row, "Cleared" item 2, running-agent rule) and T330.3 (dependency line) updated; T152 is done and already states these rules.
