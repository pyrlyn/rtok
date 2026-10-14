# rtok vs claude-mem

Competitor: [claude-mem](https://github.com/thedotmack/claude-mem) · category: Memory · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs claude-mem

**What claude-mem does.** JS plugin + MCP + daemon (Apache-2.0): LLM-extracted observations in SQLite + FTS5 + Chroma, progressive disclosure, cloud sync (v13.25.1, 4 MCP tools).

**What rtok does here.** `memory`: agent-written notes in SQLite FTS5, titles → ids → bodies, 3 tools, no model calls.

### claude-mem: pros
- Automatic extraction; vectors and semantic search today.

### claude-mem: cons
- Spends tokens (LLM calls) to build memory.
- Daemon and Chroma.

### rtok: pros
- Zero model calls; tiny SessionStart cost (100 B vs 371 866 B full injection at N=100).

### rtok: cons
- No LLM extraction or embeddings model; recall is lexical (optional hash-embed).

## Verdict

**claude-mem is better if you want automatic memory; rtok is cheaper and simpler.**

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims 87 % (own banner) | `research.md` §4 matrix |
| Third-party "~10×" retrieval comparison (mindstudio.ai), not a bill delta | `research.md` §9.1 |
| rtok plant-and-recall 20/20 at N=1/10/30/100; superseded 0 | `docs/comparison.md` §2 Memory; `research.md` §14 T69.3 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Its memory is built by LLM calls (Claude); a fair run needs paid API traffic.
