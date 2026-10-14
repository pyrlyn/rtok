# rtok vs code-review-graph

Competitor: [code-review-graph](https://github.com/tirth8205/code-review-graph) · category: Code graphs · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs code-review-graph

**What code-review-graph does.** Python MCP server (MIT): tree-sitter graph in SQLite, impact radius, minimal context, communities; 30 tools; optional embeddings.

**What rtok does here.** `graph` with `impact` and `graph_diff`, 7 operations in 165 description tokens.

### code-review-graph: pros
- Genuinely clever impact analysis.
- Rich review-oriented operations.

### code-review-graph: cons
- 30 tools ≈ 2 295 description tokens every turn — the most expensive server measured.
- F1 0.69 against its own edges (own README).

### rtok: pros
- ~14× smaller tool bill; Rust, hook-fast.

### rtok: cons
- Less review-specific tooling (communities).

## Verdict

**rtok is better on cost; code-review-graph has richer review features.**

**Gap to close:** competitor ahead on richer review/blast-radius features (~30 MCP tools). Tracked as [T664](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 30 tools, ~2 295 description tokens | `docs/comparison.md` §2 MCP table |
| Claims 65× median (36–376×, 6 repos); F1 0.69 vs own edges | `research.md` §4 matrix |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `pending`. Numbers below stay pending until a real `python3 bench/competitors/run_all.py code-review-graph` run (Colima/Docker `--cpus 4 --memory 8g`, or the manual `workflow_dispatch` job).

| side | tokens saved |
|---|---|
| rtok | pending (no run yet) |
| code-review-graph | pending (no run yet) |

Harness: [`bench/competitors/code-review-graph/`](../../bench/competitors/code-review-graph/) (`run.py` on the host, `Dockerfile`, `bench.py` inside the container; shared [`bench/competitors/_lib/`](../../bench/competitors/_lib/); entry point [`run_all.py`](../../bench/competitors/run_all.py)).

<!-- competitor-bench:end -->
