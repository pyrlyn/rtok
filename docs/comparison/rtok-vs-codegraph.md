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

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims −88 % tool calls, −62 % tokens over 7 repos (README, confirmed as stated) | `research.md` §4 matrix, §7 |
| 97 MB `.codegraph/` for one repo | `research.md` §4 matrix |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| codegraph | no data |

<!-- competitor-bench:end -->
