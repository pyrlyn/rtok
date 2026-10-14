# rtok vs ponytail

Competitor: [ponytail](https://github.com/DietrichGebert/ponytail) · category: Prompt level · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs ponytail

**What ponytail does.** A YAGNI-ladder prompt file (MIT) that tells the model to write less code.

**What rtok does here.** Native `yagni` mode (145 tokens) with a typed ladder in `src/modes`, inside the 800-token budget.

### ponytail: pros
- Zero runtime; cheapest lever (output tokens are 20–39 % of the bill).

### ponytail: cons
- Only its own bench (Haiku 4.5, n=4).

### rtok: pros
- Typed ladder 14/14 vs naive always-Minimum 4/14 on fixtures.

### rtok: cons
- Fixture bench only; no live bill delta.

## Verdict

**Same idea; rtok's version wins its fixture bench, neither has a live bill number.**

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims −54 % LOC, −22 % tokens (own bench, Haiku 4.5, n=4) | `research.md` §4 matrix; `docs/comparison.md` §2 |
| rtok ladder 14/14 vs 4/14 | `docs/comparison.md` §2 Prompt-level table |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Its effect is on model output; measuring it needs live model calls.
