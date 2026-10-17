# rtok vs bifrost

> **bifrost is not a competitor of rtok because it is an LLM gateway with semantic cache, not a local agent token-reduction stack.**


Competitor: [bifrost](https://github.com/maximhq/bifrost) · category: Gateways · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs bifrost

**What bifrost does.** Go LLM gateway (Apache-2.0) with routing, plugins and a semantic response cache (Redis, 0.9 similarity threshold).

**What rtok does here.** Local proxy with usage capture; semantic cache opt-in and off by default (P31: 0 hits at 0.99); routing (D9) planned.

### bifrost: pros
- Production router and gateway today.
- Fast Go implementation.

### bifrost: cons
- Semantic cache is a category error for coding agents: contexts never repeat, so a hit is a wrong answer.

### rtok: pros
- Token reduction and measurement, which bifrost does not do.

### rtok: cons
- No router, no Flex `service_tier` prepare, no Batch observe yet.

## Verdict

**Different products.** For routing use bifrost; for cutting coding-agent tokens rtok. Its semantic cache should stay off for agents.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| P31: 0 hits at threshold 0.99 | `research.md` §9.3 Gateways row |
| Capability table vs Portkey and LiteLLM | `docs/comparison.md` §5 "Batch, Flex, and routing gateways" |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Gateway/semantic-cache product; not a local token-reduction tool on a fixed corpus.

| side | tokens saved |
|---|---|
| rtok | no data |
| bifrost | no data |

<!-- competitor-bench:end -->
