# rtok vs Portkey

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

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Tool description compression + allowlist, 18–28 % claimed | `research.md` §9.1 |
| Capability table | `docs/comparison.md` §5 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Hosted/gateway product; its value shows only on live provider traffic.
