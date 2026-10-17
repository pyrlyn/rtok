# rtok vs LLMLingua-2

> **LLMLingua-2 is not a competitor of rtok because rtok declines lossy model compression for code until a measured gain exists.**


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

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims 2–20×; quality risk on code | `research.md` §4 matrix; `docs/comparison.md` §2 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Small-model compression; needs model weights/API; risky on code.

| side | tokens saved |
|---|---|
| rtok | no data |
| llmlingua-2 | no data |

<!-- competitor-bench:end -->
