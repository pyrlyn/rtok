# rtok vs graymatter

Competitor: [graymatter](https://github.com/angelnicolasc/graymatter) · category: Memory · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs graymatter

**What graymatter does.** One ~10 MB Go binary (MIT): bbolt + chromem-go, MCP + Claude Code hooks, hybrid recall (vector + keyword + recency), decay, tombstones, pinning.

**What rtok does here.** `memory` with lifecycle (retire, supersede, pin — T69.1), FTS5 + optional hash-embed.

### graymatter: pros
- Closest design to rtok; hybrid ranking with receipts; decay.

### graymatter: cons
- Its 90 % is against full-history injection, a baseline no host uses.

### rtok: pros
- Measured 20/20 recall at N=100; integrated with the rest of rtok.

### rtok: cons
- No recency/decay ranking (T69.2 closed without a scorer).

## Verdict

**Close; rtok has the stronger measured recall on its own bench, graymatter the richer ranking.** Never cite graymatter's 83 % as rtok's.

**Gap to close:** competitor ahead on hybrid ranking and forgetting. Tracked as [T669](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims ~6 960 → ~670 tokens per session at 100 sessions ("90 %"), 83 % recall of a fact 96 sessions old, 0 % superseded | `research.md` §14 |
| rtok: FTS5 and hybrid 20/20 at N=1/10/30/100; SessionStart 100 B vs 371 866 B | `research.md` §14 T69.3 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| graymatter | no data |

<!-- competitor-bench:end -->
