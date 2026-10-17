# rtok vs LiteLLM

> **LiteLLM is not a competitor of rtok because it is an LLM gateway with model aliases/routing, not a local token-reduction agent stack.**


Competitor: [LiteLLM](https://github.com/BerriAI/litellm) · category: Gateways · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs LiteLLM

**What LiteLLM does.** Python gateway and SDK with model aliases, router, fallbacks and caching.

**What rtok does here.** Proxy with usage capture; routing planned.

### LiteLLM: pros
- First-class router and model aliases.

### LiteLLM: cons
- Not a coding-agent token tool.

### rtok: pros
- Measured token cuts.

### rtok: cons
- No router yet.

## Verdict

**Different products.**

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Cited for `proxy` (1.7x, 2026-09-02); 18–28 % description-compression claim with Portkey | `research.md` §6, §9.1 |
| Capability table | `docs/comparison.md` §5 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: LLM gateway/router.

| side | tokens saved |
|---|---|
| rtok | no data |
| litellm | no data |

<!-- competitor-bench:end -->
