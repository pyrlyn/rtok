# rtok vs token-optimizer-mcp

Competitor: [token-optimizer-mcp](https://github.com/ooples/token-optimizer-mcp) · category: Read, search and shell MCP servers · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs token-optimizer-mcp

**What token-optimizer-mcp does.** A separate TypeScript MCP server (v7.0.0, 74 tools) for token savings; default mode switched from `enforce` to `assist`; unrelated to the `token-optimizer` hook pile.

**What rtok does here.** 12 MCP tools for ~223 description tokens, plus hooks and proxy.

### token-optimizer-mcp: pros
- Publishes a randomized 16-task holdout bench with a quality score (rare in the field).

### token-optimizer-mcp: cons
- 74 tools: a large description bill per turn unless the host defers tools.
- Node runtime.

### rtok: pros
- Small tool list; one binary.

### rtok: cons
- No published live-bill holdout of its own yet (`docs/comparison.md` §5).

## Verdict

**Undecided on cost; rtok is leaner.** token-optimizer-mcp has the better published evidence (a holdout with quality), rtok has the far smaller fixed per-turn cost. A live A/B would decide it; neither side has one on a shared corpus.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| v7.0.0 holdout: cheaper on 11/16 tasks, median 0.926× cost, quality 0.994 vs control (own bench) | `research.md` §9.1, https://github.com/ooples/token-optimizer-mcp/releases/tag/v7.0.0 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Needs a live MCP client plus LLM turns; no shared offline corpus.

| side | tokens saved |
|---|---|
| rtok | no data |
| token-optimizer-mcp | no data |

<!-- competitor-bench:end -->
