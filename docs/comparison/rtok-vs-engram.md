# rtok vs engram

Competitor: [engram](https://github.com/Gentleman-Programming/engram) · category: Memory · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs engram

**What engram does.** Go MCP + HTTP server (MIT): agent-written notes in SQLite FTS5, topic keys, git sync, relation judging, session summaries; 18 tools.

**What rtok does here.** `memory`: same storage idea with 3 tools; adopted topic-key upsert (T66.1) and export (T66.2).

### engram: pros
- Rich feature set (topic keys, git sync, TUI, Postgres).

### engram: cons
- 18 tools ≈ 1 865 description tokens every turn.
- Several features spend model output on bookkeeping.

### rtok: pros
- Same core with ~1/6 of the tools.

### rtok: cons
- No relation judging, pinned-first recall or project identity from origin remote.

## Verdict

**rtok is better on cost with the useful parts adopted; engram has more memory features.**

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 18 tools, ~1 865 description tokens (`rtok doctor`, 2026-09-09) | `docs/comparison.md` §2 MCP table; `research.md` §13 |
| engram's docs carry no token-saving number | `research.md` §13 |

## Own measurements

Not measured. No script in this pass.

Feasible later: A plant-and-recall run (like `tests/memory_bench.rs`) against engram's CLI is LLM-free; not written in this pass.
