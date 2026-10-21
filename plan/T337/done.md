In the plan, T329 §6b (branch `docs/plan-graph-projects`, ~lines 787-791, from PR #540 (T329), not merged yet) says later requests "do not re-probe the modes that failed", the cache "is kept until that process restarts" and "nothing else invalidates it". T329 §8d (~lines 917-923) says a background check every 60 s detects **unreachable** (SSH root stops answering) and **backend down**, and "when the project comes back, the alert clears automatically"; §8f (~line 943) scores "Backend alive" from the same record. These contradict each other because detecting an unreachable SSH host or a recovered backend requires probing again, which §6b forbids; under §6b a backend-down alert can never clear without a restart.

Goal: research both approaches, compare trade-offs, recommend one, then update the conflicting tasks. Do not change either task before the decision.

Check: the recommendation and the chosen approach are recorded in this card (or as a decision row), and every task named above is updated so the plan no longer contradicts itself.

Decision (2026-10-09, creator): requests never re-probe; only the T329 §8d background health check re-probes (research option 2). Each interval it runs cheap checks (the root exists, the server binary appeared on `PATH`, a running server is alive) and restarts a server only on a change or on a backoff that starts at 60 s and is capped; an alert clears after two consecutive good checks. The "100 requests, zero probes" test stays. The capability record is mirrored into the store, so the MCP server, `rtok web`, the CLI and `rtok doctor` see the same record.

Updated in `plan.md`: T329 §6b (heading, in-memory plus store mirror, downgrade lasts until two good checks, config changes and the health check as the only updaters, `checked_at`/`next_probe_at`), §8d detection (cheap tier, backoff, every process that hosts graph) and recovery (two good checks), §8f fix text, the T329 acceptance lines for a mid-session crash, the capability cache and alerts, the split note, and the T329.11, T329.17 and T329.19 cards (`reprobe(project)` entry point, store mirror). The T329.10 card is unchanged: its SSH reachability probe follows T335's outcome. The optional decision row was not added.

Status: done 2026-10-09
Model: Claude Code / sonnet-5.5
