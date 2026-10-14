# rtok vs tinymemory / openhuman

Competitor: [tinymemory / openhuman](https://github.com/tinyhumansai/openhuman) · category: Memory · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs tinymemory / openhuman

**What tinymemory / openhuman does.** openhuman's tinymemory: an admission gate on what to remember and a 7-day half-life freshness decay; the same org ships tinyjuice (HTML/RSS → text).

**What rtok does here.** `memory` with lifecycle; recency ranking not shipped.

### tinymemory / openhuman: pros
- Admission gate and decay.

### tinymemory / openhuman: cons
- No independent numbers.

### rtok: pros
- Measured recall bench.

### rtok: cons
- No decay (T69.2 closed without code).

## Verdict

**Ideas, not a head-to-head.** rtok tracked the gate (I-59) and decay (I-60) and measured tinyjuice's idea (I-71) without building it.

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| tinyjuice: 77.0 % smaller HTML/RSS, self-reported on a 15.4 MB / 166-case corpus; measured by rtok 2026-09-18 (T71.1), not built | `ideas.md` I-71 |
| Admission gate, 7-day half-life | `ideas.md` I-59, I-60 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: The memory features are design ideas without a runnable comparable surface.
