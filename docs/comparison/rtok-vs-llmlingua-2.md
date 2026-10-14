# rtok vs LLMLingua-2

Competitor: [LLMLingua-2](https://github.com/microsoft/LLMLingua) · category: Formats / compression · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs LLMLingua-2

**What LLMLingua-2 does.** Microsoft's token pruning with a small model (MIT), 2–20× claimed.

**What rtok does here.** Lossless cuts only; LLM compression is v0.2+ and gated on beating the lossless path.

### LLMLingua-2: pros
- High compression ratios.

### LLMLingua-2: cons
- Lossy; quality risk on code; needs a model.

### rtok: pros
- Lossless, reversible.

### rtok: cons
- Nothing comparable in ratio.

## Verdict

**Different risk profile; rtok declines it for code until a bench shows a gain.**

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims 2–20×; quality risk on code | `research.md` §4 matrix; `docs/comparison.md` §2 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Needs a model download and a quality metric on agent tasks; compression ratio alone would be an unfair comparison against lossless cuts.
