# rtok vs OpenViking

Competitor: [OpenViking](https://github.com/volcengine/OpenViking) · category: Memory / context DB · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs OpenViking

**What OpenViking does.** Python/Rust context database (AGPL-3.0): L0/L1/L2 tiered loading and session compression; SDK + MCP.

**What rtok does here.** Progressive disclosure in `memory` and `read`, `archive` for session context.

### OpenViking: pros
- Tiered context model.

### OpenViking: cons
- Uses an LLM; AGPL.
- No independent measurement.

### rtok: pros
- LLM-free; permissive.

### rtok: cons
- No tiered document store.

## Verdict

**Different scope; no shared evidence.**

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims 34–91 %; none measured | `research.md` §4 matrix |
| "OpenViking is Rust" refuted (78 % Python) | `research.md` §7 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Session compression needs LLM calls.
