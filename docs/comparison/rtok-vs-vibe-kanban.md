# rtok vs Vibe Kanban

> **Vibe Kanban is not a competitor of rtok because it is a task/session coordination tool, not a token-reduction hook/MCP/proxy stack; rtok's docs treat it as an idea source (`research.md` §28).**


Competitor: [Vibe Kanban](https://github.com/BloopAI/vibe-kanban) · category: Tasks and session sync · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs Vibe Kanban

**What Vibe Kanban does.** SQLite kanban board for agents; README says it is sunsetting.

**What rtok does here.** rtok's task/session work (`research.md` §28): `handoff` MCP tool, SessionEnd `session:<id>` note with SessionStart recall (T71.2), `memory export/import`, worktree claims (D34), with `plan.md` as the source of truth (proposed option 1a).

### Vibe Kanban: pros
- Board UI.

### Vibe Kanban: cons
- Being sunset; no deps.

### rtok: pros
- Reuses parts rtok already owns (handoff, checkpoint extract, inject, archive); one SQLite file per machine (D8); token-saving priming within the inject budget.

### rtok: cons
- The task layer (claim, ready queue, typed deps) is a proposal awaiting a creator decision (§28.5), not shipped.

## Verdict

**Not a token-reduction competitor; an idea source.** rtok's verdict on its features is in `research.md` §28.4; for Vibe Kanban the comparison is about coordination, which rtok has only partly shipped.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Storage, claim, dependencies and maintenance status as of 2026-09-30 | `research.md` §28.3 |
| Feature union and take/skip verdicts | `research.md` §28.4 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Coordination/session tool; no shared token-reduction metric under this suite's rules.

| side | tokens saved |
|---|---|
| rtok | no data |
| vibe-kanban | no data |

<!-- competitor-bench:end -->
