# rtok vs SpecStory

Competitor: [SpecStory](https://github.com/specstoryai/getspecstory) · category: Tasks and session sync · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs SpecStory

**What SpecStory does.** Markdown history for ~14 agents, optional cloud; cross-agent resume claimed (unverified) (Apache-2.0, v2.15.1).

**What rtok does here.** rtok's task/session work (`research.md` §28): `handoff` MCP tool, SessionEnd `session:<id>` note with SessionStart recall (T71.2), `memory export/import`, worktree claims (D34), with `plan.md` as the source of truth (proposed option 1a).

### SpecStory: pros
- Wide host coverage; readable history.

### SpecStory: cons
- Resume claim unverified; cloud optional.

### rtok: pros
- Reuses parts rtok already owns (handoff, checkpoint extract, inject, archive); one SQLite file per machine (D8); token-saving priming within the inject budget.

### rtok: cons
- The task layer (claim, ready queue, typed deps) is a proposal awaiting a creator decision (§28.5), not shipped.

## Verdict

**Not a token-reduction competitor; an idea source.** rtok's verdict on its features is in `research.md` §28.4; for SpecStory the comparison is about coordination, which rtok has only partly shipped.

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Storage, claim, dependencies and maintenance status as of 2026-09-30 | `research.md` §28.3 |
| Feature union and take/skip verdicts | `research.md` §28.4 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: No shared metric with a token-reduction tool: these are coordination and session tools, and none of rtok's docs define a workload on which both could be timed or token-counted.
