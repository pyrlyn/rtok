# rtok vs Anthropic native features

Competitor: [Anthropic native features](https://docs.anthropic.com/) · category: Platform · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs Anthropic native features

**What Anthropic native features does.** Prompt caching, context editing (`clear_tool_uses`, `clear_thinking`), the memory tool, deferred tools (Tool Search), server-side compaction, auto-memory in Claude Code.

**What rtok does here.** Aligns with them: byte-stable injection, cache-preserving proxy rewrites, `doctor` flags when Tool Search is off and names duplicated native features.

### Anthropic native features: pros
- Free, first-party, no install.

### Anthropic native features: cons
- One host; no cross-tool ledger.

### rtok: pros
- Works across 24 hosts; measures.

### rtok: cons
- Duplicates some native features on Claude Code (auto-memory).

## Verdict

**Use both.** The platform is what every tool competes with; rtok's job is to not fight it.

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Cache hit rate 97.5 % measured | `docs/comparison.md` §4, `docs/prompt-cache.md` |
| Tool Search loads ~3 K tokens per query; memory tool ~2.5 K overhead; cache read 0.025× on Fable/Mythos 5.1 | `research.md` §9.2 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Platform feature; measurable only on live API traffic.
