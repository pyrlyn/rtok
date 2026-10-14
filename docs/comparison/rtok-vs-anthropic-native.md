# rtok vs Anthropic native features

> **Anthropic native features are not a competitor of rtok because they are host platform capabilities (prompt cache, context editing, memory tool) that rtok is designed to work with.**


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

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Cache hit rate 97.5 % measured | `docs/comparison.md` §4, `docs/prompt-cache.md` |
| Tool Search loads ~3 K tokens per query; memory tool ~2.5 K overhead; cache read 0.025× on Fable/Mythos 5.1 | `research.md` §9.2 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Platform features of the host; not a third-party tool to bench locally.

| side | tokens saved |
|---|---|
| rtok | no data |
| anthropic-native | no data |

<!-- competitor-bench:end -->
