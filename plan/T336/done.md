In the plan, T329 Terms (branch `docs/plan-graph-projects`, ~line 679, from PR #540 (T329), not merged yet) says "**Selected project**: the project the graph page (and, by default, the CLI and MCP tools) answers for", and T329 §7 (~line 799) says "Without it, the project is the caller's current directory (agents keep today's behaviour)". These contradict each other because the selection is stored globally in the store (§2), so one rule makes an agent's MCP call follow whatever project the user last picked in the web UI and the other makes it follow the agent's cwd; the two give different answers whenever they differ.

Goal: research both approaches, compare trade-offs, recommend one, then update the conflicting tasks. Do not change either task before the decision.

Check: the recommendation and the chosen approach are recorded in this card (or as a decision row), and every task named above is updated so the plan no longer contradicts itself.

Recommendation: the caller's current directory. The selection is one global value in the store (T329 §2), so making it the default would send every agent's MCP call to whatever project the user last picked in the web UI, and two agents in two repositories would answer for the same project. The cwd keeps today's behaviour and is per caller. The web UI selection drives only the graph page. Callers that need another project pass `project` explicitly.

Decision (creator, 2026-10-06): the cwd project plus its links is the default for every graph command and graph MCP tool; the web UI selection never replaces it. T329 Terms, the T329 split note and the T329.4 card now say so.

Check result (2026-10-06, Claude Code / opus-5.5): decision recorded here; T329 Terms ("Selected project"), the T329 split note (T336 no longer gates T329.4) and the T329.4 card updated; no other card mentions T336.
