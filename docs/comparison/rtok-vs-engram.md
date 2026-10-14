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

**Gap to close:** competitor ahead on richer agent-written memory features (HTTP + MCP). Tracked as [T667](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 18 tools, ~1 865 description tokens (`rtok doctor`, 2026-09-09) | `docs/comparison.md` §2 MCP table; `research.md` §13 |
| engram's docs carry no token-saving number | `research.md` §13 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `pending`. Numbers below stay pending until a real `python3 bench/competitors/run_all.py engram` run (Colima/Docker `--cpus 4 --memory 8g`, or the manual `workflow_dispatch` job).

| side | tokens saved |
|---|---|
| rtok | pending (no run yet) |
| engram | pending (no run yet) |

Harness: [`bench/competitors/engram/`](../../bench/competitors/engram/) (`run.py` on the host, `Dockerfile`, `bench.py` inside the container; shared [`bench/competitors/_lib/`](../../bench/competitors/_lib/); entry point [`run_all.py`](../../bench/competitors/run_all.py)).

<!-- competitor-bench:end -->
