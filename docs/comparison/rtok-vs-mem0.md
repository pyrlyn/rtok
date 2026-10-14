# rtok vs mem0 / OpenMemory

Competitor: [mem0 / OpenMemory](https://github.com/mem0ai/mem0) · category: Memory · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs mem0 / OpenMemory

**What mem0 / OpenMemory does.** Python memory layer (Apache-2.0): LLM fact extraction plus vectors; OpenMemory MCP usually needs Docker and Qdrant.

**What rtok does here.** `memory`: FTS5 notes, no model calls.

### mem0 / OpenMemory: pros
- Semantic recall and fact extraction today; large ecosystem.

### mem0 / OpenMemory: cons
- States its own overhead: 6.7–7.0 K tokens + ~1 s per add.
- Not local-first by default.

### rtok: pros
- No per-add model cost; one SQLite file.

### rtok: cons
- No vector search by default.

## Verdict

**mem0 is better for semantic memory; rtok is far cheaper per note.**

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 6.7–7.0 K tokens + ~1 s per add (own docs, v3.1.8) | `research.md` §9.1 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Adds require LLM calls; a fair run needs API keys.
