# rtok vs OpenViking

> **openviking is not a competitor of rtok because it is a multi-level context database, not a local agent hook/MCP/proxy token-reduction stack.**


Competitor: [OpenViking](https://github.com/volcengine/OpenViking) · category: Memory / context DB · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs OpenViking

**What OpenViking does.** Python/Rust context database (AGPL-3.0): L0/L1/L2 tiered loading and session compression; SDK + MCP.

**What rtok does here.** Progressive disclosure in `memory` and `read`, `archive` for session context.

### OpenViking: pros
- Tiered context model.

### OpenViking: cons
- Uses an LLM; AGPL.
- No independent measurement.

### rtok: pros
- LLM-free; permissive.

### rtok: cons
- No tiered document store.

## Verdict

**Different scope; no shared evidence.**

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims 34–91 %; none measured | `research.md` §4 matrix |
| "OpenViking is Rust" refuted (78 % Python) | `research.md` §7 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Multi-level context DB with session compression; no shared offline metric.

| side | tokens saved |
|---|---|
| rtok | no data |
| openviking | no data |

<!-- competitor-bench:end -->
