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

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 30 tools, ~2 295 description tokens | `docs/comparison.md` §2 MCP table |
| Claims 65× median (36–376×, 6 repos); F1 0.69 vs own edges | `research.md` §4 matrix |

## Own measurements

Not measured. No script in this pass.

Feasible later: Index time / query latency / size are LLM-free; not written in this pass.
