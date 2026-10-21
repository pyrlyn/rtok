---
status: todo
priority: P3
complexity: 2
readiness: 60%
agent:
---

# T124. Realized `tools_rewrite` saving as a dated `research.md` row

6.2 % (T59.5) is the ceiling, not a saving: no dated row shows what `[proxy.tools_rewrite]` removes with the default `max_description_tokens = 60`. Precondition, by the creator: turn it on for this machine's proxy for at least 20 sessions. Then sum the `kind = tools_rewrite` Measurement rows against session input for the same window (`rtok stats` / `rtok gain`, dated command in the row), and write one row into `research.md` §2 next to the T59.5 row; update `docs/comparison.md` only if it cites the number. If the realized share is under the 3 % gate, say so in the row and leave the default off.
Check: the row cites the command, date, sessions, before/after tokens and the share; no number in prose without it.

Progress (2026-10-15, `research.md` §2): a local reference run (20 one-request sessions through a scratch proxy to LM Studio) puts the saving at 16.2 K tokens per request (42.0 % of a bare request, API-counted); the row says it is not the gate. Left: the creator's 20 real sessions on this machine's own proxy, and an answer-quality check with truncated built-in tool descriptions. Default stays off.

Autonomous attempt (2026-10-21): not doable by an agent. No `rtok proxy` was running on this machine, the worktree sandbox cannot read the machine's rtok store or run the installed `rtok`, and 20 real sessions are paid live runs by the creator. No code is missing: `[proxy.tools_rewrite]` already records `proxy` / `tools_rewrite` Measurement rows, and `rtok stats --since <N>d --plugin proxy --json` sums them for the window (`--since` takes a duration, not a date). For the creator: set `[proxy.tools_rewrite] enabled = true` on this machine's proxy, note the start date, work at least 20 normal sessions, then run that command with `<N>` covering the window and write the row into `research.md` §2 with the date, sessions, est. before/after tokens and the share of session input. Under 3 %: the row says so and the default stays off. Nothing was added to `research.md`.
