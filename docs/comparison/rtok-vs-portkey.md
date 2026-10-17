# rtok vs Portkey

> **Portkey is not a competitor of rtok because it is an LLM gateway (keys, routing, fallbacks), not a local token-reduction agent stack.**


Competitor: [Portkey](https://github.com/Portkey-AI/gateway) · category: Gateways · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs Portkey

**What Portkey does.** LLM gateway: virtual keys, routing, fallbacks, caching, tool-description compression + allowlist.

**What rtok does here.** Proxy with usage capture; routing D9 planned.

### Portkey: pros
- Production router and fallbacks today.

### Portkey: cons
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
| Tool description compression + allowlist, 18–28 % claimed | `research.md` §9.1 |
| Capability table | `docs/comparison.md` §5 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Hosted LLM gateway.

| side | tokens saved |
|---|---|
| rtok | no data |
| portkey | no data |

<!-- competitor-bench:end -->
