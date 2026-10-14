# rtok vs codegraph

Competitor: [codegraph](https://github.com/colbymchenry/codegraph) · category: Code graphs · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs codegraph

**What codegraph does.** Code graph in `.codegraph/codegraph.db` (SQLite + FTS5) with one `codegraph_explore` MCP tool and a CLI (MIT).

**What rtok does here.** `graph` plugin, SQLite only, 7 operations in 165 description tokens.

### codegraph: pros
- One tool, small description cost.
- Published multi-repo claim (−88 % tool calls, −62 % tokens).

### codegraph: cons
- 97 MB `.codegraph/` for one repo (cross-code).
- Claims are vendor numbers.

### rtok: pros
- Impact, callers and diff operations; hook-path latency budget.

### rtok: cons
- No published with/without live bench yet.

## Verdict

**Close; no shared measurement.** Similar design (SQLite, few tools). rtok's index is smaller per the author's checks, codegraph has a published (vendor) with/without claim.

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims −88 % tool calls, −62 % tokens over 7 repos (README, confirmed as stated) | `research.md` §4 matrix, §7 |
| 97 MB `.codegraph/` for one repo | `research.md` §4 matrix |

## Own measurements

Not measured. No script in this pass.

Feasible later: Index time / query latency / size are LLM-free; not written in this pass.
