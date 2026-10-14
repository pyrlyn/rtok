# rtok vs headroom

Competitor: [headroom](https://github.com/headroomlabs-ai/headroom) · category: Compressing proxies · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs headroom

**What headroom does.** A Python/Rust proxy (Apache-2.0) that compresses JSON and code in API requests with a cache-aligned live zone (shrink the tail, keep the cached prefix byte-identical) and a retrieve path (CCR); also MCP and wrap modes, ASGI middleware, a hosted proxy.

**What rtok does here.** `proxy` + `archive` plugins implement the same live-zone invariant; the proxy records provider `usage` as ground truth.

### headroom: pros
- The sharpest idea in the field (cache-aligned live zone).
- Reversible (retrieve).

### headroom: cons
- Claims up to 95 %; the author's own meter showed 11.3 % over 30 days and 3.2 % on one day.
- Python runtime on the request path.

### rtok: pros
- Same invariant in one Rust binary, plus hooks and MCP.
- Usage-based ledger.

### rtok: cons
- headroom's JSON/code compressors are broader.

## Verdict

**Roughly equal on mechanism; rtok is better as an integrated, measured stack.** headroom invented the live zone; rtok adopted it and adds measurement and the other two surfaces.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claimed up to 95 %; measured 11.3 % over 30 d, 3.2 % that day (author's meter) | `research.md` §4 matrix; `docs/comparison.md` §2 |
| Later claims 21–57 % on own benchmarks, 20 % "for coding agents" | `research.md` §9.1 |
| 3 tools, ~161 description tokens | `docs/comparison.md` §2 MCP table |
| Cache hit rate 97.5 % measured on this machine | `docs/comparison.md` §4 item 6, `docs/prompt-cache.md` |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Hosted/proxy product; fair token bills need paid API keys.

| side | tokens saved |
|---|---|
| rtok | no data |
| headroom | no data |

<!-- competitor-bench:end -->
