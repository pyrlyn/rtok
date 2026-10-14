# rtok vs lean-ctx

Competitor: [lean-ctx](https://github.com/yvgude/lean-ctx) · category: Read, search and shell MCP servers · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs lean-ctx

**What lean-ctx does.** Rust MCP server plus hooks (Apache-2.0) for bounded file reads (10 read modes), search, a shell wrapper with 95+ patterns, deduplicated re-reads (~13 tokens) and handoff tools; it also denies native Grep/Glob.

**What rtok does here.** `read` plugin: 3 MCP tools, 6 read modes (`full`, `lines`, `map`, `signatures`, `diff`, `stripped`), sha256 dedup, root guard, no banner; the injection budget is 800 tokens per turn, byte-stable.

### lean-ctx: pros
- More read modes (`density`, a mode predictor learned from past sessions).
- Sub-agent handoff tools (`ctx_handoff`, `ctx_agent`).
- Re-read of an unchanged file costs ~13 tokens.

### lean-ctx: cons
- ~3.0–3.1 K tokens of banner and instructions injected per turn, cached or not.
- 12 tools ≈ 697 description tokens every turn on the author's machine.
- Measured 0 output saved on the author's machine.

### rtok: pros
- Fixed cost per turn ~10× smaller (whole budget 800 tokens; ~223 description tokens for 12 tools).
- Delta reads (T58.1) and measurement per call.

### rtok: cons
- Fewer read modes; no learned mode predictor.

## Verdict

**rtok is better on this workload.** The per-turn fixed cost of lean-ctx is larger than what its read modes save here; rtok copies the read modes that paid off and keeps the per-turn cost small.

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| lean-ctx injects ~3.1 K tokens per turn; 0 output saved | `research.md` §4 matrix; `docs/comparison.md` §2 |
| 12 tools, ~697 description tokens per turn (`rtok doctor`, 2026-09-09) | `docs/comparison.md` §2 MCP table |
| v3.10.2: 30+ tools, ~13-token re-reads, ~3.0 K fixed per-session overhead (own README) | `research.md` §9.1 |
| lean-ctx ≈ 27 % of the tool table in the T59.4 re-measure (885 sessions) | `research.md` §9.3 |

## Own measurements

Not measured. No script in this pass.

Feasible later: A fair bench needs an MCP client replaying a fixed read/search trace against both servers and counting result + description tokens; LLM-free and doable, not written in this pass.
