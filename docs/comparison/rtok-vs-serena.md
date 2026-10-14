# rtok vs serena

Competitor: [serena](https://github.com/oraios/serena) · category: Code graphs · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs serena

**What serena does.** Python MCP server (MIT) with LSP-backed symbol tools for 40+ languages (v1.6.0): find symbol, references, replace symbol body.

**What rtok does here.** `graph` plugin: tree-sitter tags into SQLite; 7 operations (`symbol`, `callers`, `impact`, `outline`, `explore`, `graph_diff`, `graph_export`) in 165 description tokens; hook ≤ 10 ms.

### serena: pros
- Most precise symbol resolution available (LSP).
- Edit tools at symbol level.

### serena: cons
- 22 tools ≈ 1 494 description tokens every turn.
- Timed out at 30 s on the author's machine.

### rtok: pros
- ~9× smaller tool bill; fast SQLite index.
- Definition recall/precision 1.000 on a 30-symbol set.

### rtok: cons
- Reference recall 0.351 originally (0.924 after T387), still below LSP-grade.

## Verdict

**serena is better at precision; rtok is better on cost.** Use serena when exact references matter more than ~1.5 K tokens per turn (`docs/comparison.md` §7).

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 22 tools, ~1 494 description tokens | `docs/comparison.md` §2 MCP table |
| Times out at 30 s here; most precise | `research.md` §4 matrix |
| rtok reference recall 0.351 → 0.924 after T387 | `docs/comparison.md` §5; `research.md` §9.3 Code graph row |

## Own measurements

Not measured. No script in this pass.

Feasible later: Index time, query latency and index size on the pinned corpus are LLM-free; serena needs language servers in the image. Not written in this pass.
