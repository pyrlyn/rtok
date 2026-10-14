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

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims −54 % LOC, −22 % tokens (own bench, Haiku 4.5, n=4) | `research.md` §4 matrix; `docs/comparison.md` §2 |
| rtok ladder 14/14 vs 4/14 | `docs/comparison.md` §2 Prompt-level table |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Prompt-only; live bill needs a model.

| side | tokens saved |
|---|---|
| rtok | no data |
| ponytail | no data |

<!-- competitor-bench:end -->
