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

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims ~6 960 → ~670 tokens per session at 100 sessions ("90 %"), 83 % recall of a fact 96 sessions old, 0 % superseded | `research.md` §14 |
| rtok: FTS5 and hybrid 20/20 at N=1/10/30/100; SessionStart 100 B vs 371 866 B | `research.md` §14 T69.3 |

## Own measurements

Not measured. No script in this pass.

Feasible later: Its benchmark is LLM-free (`go run ./benchmarks/token_count`, keyword embedder); a side-by-side plant-and-recall is feasible but not written in this pass.
