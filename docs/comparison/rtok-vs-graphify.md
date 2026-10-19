# rtok vs graphify

> **graphify is not a competitor of rtok because it builds LLM-assisted code maps, not a measured agent token-reduction loop.**


Competitor: [graphify](https://github.com/Graphify-Labs/graphify) · category: Code graphs · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs graphify

**What graphify does.** Python tool: 37 tree-sitter grammars plus LLM extraction for docs into JSON, Leiden communities, an HTML map; CLI, MCP and plugin.

**What rtok does here.** `graph` (code only, no LLM), `graph_export`.

### graphify: pros
- Covers docs as well as code.
- Visual map.

### graphify: cons
- Docs layer needs an LLM.
- Not installed or measured by rtok's survey.

### rtok: pros
- LLM-free, measured.

### rtok: cons
- No docs graph.

## Verdict

**Different scope.** graphify maps code and docs with an LLM; rtok indexes code without one. No shared measurement.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claim: "code maps for free"; not installed, no measurement | `research.md` §4 matrix |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Uses LLM calls to build maps; not fairly measurable offline.

| side | tokens saved |
|---|---|
| rtok | no data |
| graphify | no data |

<!-- competitor-bench:end -->
