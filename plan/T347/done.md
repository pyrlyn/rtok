In the plan, D16 (plan.md@966f067 line 694) says "**One task = one PR.** Each task gets its own branch ... and lands through its own pull request". T275 (plan.md@966f067 lines 193-197, PR A-E), T276 (plan.md@966f067 lines 278-283, PR 1-5), T277 (plan.md@966f067 lines 310-315, PR 1-6), T279 (plan.md@966f067 lines 403-407, PR 1-4), T283-T287 (two PRs each) and T329 §10 ("As PRs, backend first", from PR #540 (T329), not merged yet) plan several PRs for one task, while T310 splits its work into subtasks to keep one PR per task. These contradict each other because the rule and the plans disagree on what may land under one task id and when the `plan.md` → `done.md` move happens.

Goal: research both approaches, compare trade-offs, recommend one, then update the conflicting tasks. Do not change either task before the decision.

Check: the recommendation and the chosen approach are recorded in this card (or as a decision row), and every task named above is updated so the plan no longer contradicts itself.

Decision (creator, 2026-10-10): D16 stays. A task that needs several PRs is split into subtasks `Tn.m`, one PR each, and the parent card stays as the epic and moves to `done.md` with its last subtask. T275, T276, T277, T279, T283 and T329 §10 say that each PR they list becomes a subtask when claimed; their lists are not rewritten (T284-T287 are no longer in `plan.md`).
