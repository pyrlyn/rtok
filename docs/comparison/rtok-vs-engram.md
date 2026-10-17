# rtok vs engram 3.4.0 (release `engram_3.4.0_linux_arm64.tar.gz`, github.com/Gentleman-Programming/engram)

Measured with `bench/c14/run_b3.py` in image `rtok-c15:3` (`bench/c14/b3.Dockerfile`: `rtok-c14b:2`
plus rustup 1.90.0 with `rust-analyzer`, which serena needs). rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit. Each case runs in a
fresh copy of the repo with a fresh `HOME`. Indexing and seeding happen in an untimed setup step on the same
MCP session. Then come the measured `tools/call`s: 1 warmup + 3 runs, median tokens of the text the model
receives (tiktoken `o200k_base`). Kept = share of the expected anchors literally present in the answer:
the `.rs` files from `git grep -w <sym>` for callers, item names for outlines, seeded fact ids for memory
search. "non-deterministic" means more than 2 tokens of spread across fresh runs; for codebase-memory-mcp
that comes from the temp checkout path inside its qualified names.

Tool catalog (`tools/list`, compact JSON): 23 tools, **5822 tokens** (rtok's own: 2910 tokens for 40 tools). Through `rtok mcp -- <server>`: 5822 tokens (passed through unchanged).

| Case | Raw | rtok | engram | graymatter |
|---|---:|---:|---:|---:|
| `memory:save 40 facts` | 749 | 360 (51.9 %, 12 ms) | 10215 (-1263.8 %, 240 ms) | 280 (62.6 %, 54 ms) |
| `memory:search 'proxy'` | 74 | 139 (-87.8 %, kept 100 %, 0 ms) | 731 (-887.8 %, kept 100 %, 5 ms) | 260 (-251.4 %, kept 100 %, 2 ms) |
| `memory:search 'SQLite WAL'` | 49 | 92 (-87.8 %, kept 100 %, 0 ms) | 506 (-932.7 %, kept 100 %, 5 ms) | 168 (-242.9 %, kept 100 %, 2 ms) |
| `memory:search 'tree-sitter'` | 129 | 172 (-33.3 %, kept 71 %, 0 ms) | 1581 (-1125.6 %, kept 100 %, 5 ms) | 174 (-34.9 %, kept 100 %, 2 ms) |
| `memory:search 'timeout'` | 59 | 112 (-89.8 %, kept 100 %, 0 ms) | 682 (-1055.9 %, kept 100 %, 4 ms) | 266 (-350.8 %, kept 100 %, 2 ms) |
| `memory:search 'blake3'` | 37 | 71 (-91.9 %, kept 100 %, 0 ms) | 326 (-781.1 %, kept 100 %, 4 ms) | 259 (-600.0 %, kept 100 %, 2 ms) |

Same 40 seeded facts (seed 1414) saved through each server's MCP save tool, then 5 searches with limit 10.
rtok's `mem_save` merges notes with the same project + kind + title by design, so 2 of the 40 facts
replaced earlier ones (the 71 % on `tree-sitter`).

Verdict: engram's save reply is about 255 tokens per note (prompt-capture hints, project resolution,
topic-key suggestions), 28× rtok's. Its search returns full observations with metadata: 4–9× rtok's tokens.
Its catalog is 5822 tokens for 23 tools (`--tools=agent` trims it to 19).

What rtok should borrow: nothing for token cost. Its session-summary / timeline tools are a feature, not a saving.
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

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| engram | no data |

<!-- competitor-bench:end -->
