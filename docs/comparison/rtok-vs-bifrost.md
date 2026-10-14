# rtok vs bifrost

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

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| P31: 0 hits at threshold 0.99 | `research.md` §9.3 Gateways row |
| Capability table vs Portkey and LiteLLM | `docs/comparison.md` §5 "Batch, Flex, and routing gateways" |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: A gateway's value is routing and caching of live provider calls; no LLM-free workload compares it with rtok.
