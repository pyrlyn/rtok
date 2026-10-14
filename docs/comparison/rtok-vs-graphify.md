# rtok vs graphify

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

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claim: "code maps for free"; not installed, no measurement | `research.md` §4 matrix |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: The docs layer needs LLM calls; the code-only half could be timed, but the tool was never measured in rtok's survey and a code-only run would not represent it.
