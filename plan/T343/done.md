In the plan, T330 item breakdown (branch `docs/plan-agents-junk`, ~line 766, from PR #541 (T330), not merged yet) says "Items are sorted by size, largest first (`--sort size|last-used|path`)", and the `rtok agents junk list` section (~line 778) says "Sorting: by space freed, largest first (`--sort name|size|freed`)". These contradict each other because one flag on one command is given two incompatible value sets and two different defaults.

Goal: research both approaches, compare trade-offs, recommend one, then update the conflicting tasks. Do not change either task before the decision.

Check: the recommendation and the chosen approach are recorded in this card (or as a decision row), and every task named above is updated so the plan no longer contradicts itself.

Decision (creator, 2026-10-10): `rtok agents junk list --sort size|last-used|path` (default `size`) sorts the items within each kind; agents are always ordered by space freed, largest first, with no flag. The T330 `list` section says so, and T330.6 no longer depends on this investigation.
