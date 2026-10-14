# rtok vs LiteLLM

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

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Cited for `proxy` (1.7x, 2026-09-02); 18–28 % description-compression claim with Portkey | `research.md` §6, §9.1 |
| Capability table | `docs/comparison.md` §5 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Gateway; its value shows only on live provider traffic.
