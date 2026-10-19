# rtok vs graymatter 0.20.1 (release `graymatter_0.20.1_linux_arm64.tar.gz`, github.com/angelnicolasc/graymatter), measured with `bench/c14/run_b3.py`

Measured with `bench/c14/run_b3.py` in image `rtok-c15:3` (`bench/c14/b3.Dockerfile`: `rtok-c14b:2`
plus rustup 1.90.0 with `rust-analyzer`, which serena needs). rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit. Each case runs in a
fresh copy of the repo with a fresh `HOME`. Indexing and seeding happen in an untimed setup step on the same
MCP session. Then come the measured `tools/call`s: 1 warmup + 3 runs, median tokens of the text the model
receives (tiktoken `o200k_base`). Kept = share of the expected anchors literally present in the answer:
the `.rs` files from `git grep -w <sym>` for callers, item names for outlines, seeded fact ids for memory
search. "non-deterministic" means more than 2 tokens of spread across fresh runs; for codebase-memory-mcp
that comes from the temp checkout path inside its qualified names.

Tool catalog (`tools/list`, compact JSON): 7 tools, **3406 tokens** (rtok's own: 2910 tokens for 40 tools). Through `rtok mcp -- <server>`: 3406 tokens (passed through unchanged).

| Case | Raw | rtok | graymatter | engram |
|---|---:|---:|---:|---:|
| `memory:save 40 facts` | 749 | 360 (51.9 %, 12 ms) | 280 (62.6 %, 54 ms) | 10215 (-1263.8 %, 240 ms) |
| `memory:search 'proxy'` | 74 | 139 (-87.8 %, kept 100 %, 0 ms) | 260 (-251.4 %, kept 100 %, 2 ms) | 731 (-887.8 %, kept 100 %, 5 ms) |
| `memory:search 'SQLite WAL'` | 49 | 92 (-87.8 %, kept 100 %, 0 ms) | 168 (-242.9 %, kept 100 %, 2 ms) | 506 (-932.7 %, kept 100 %, 5 ms) |
| `memory:search 'tree-sitter'` | 129 | 172 (-33.3 %, kept 71 %, 0 ms) | 174 (-34.9 %, kept 100 %, 2 ms) | 1581 (-1125.6 %, kept 100 %, 5 ms) |
| `memory:search 'timeout'` | 59 | 112 (-89.8 %, kept 100 %, 0 ms) | 266 (-350.8 %, kept 100 %, 2 ms) | 682 (-1055.9 %, kept 100 %, 4 ms) |
| `memory:search 'blake3'` | 37 | 71 (-91.9 %, kept 100 %, 0 ms) | 259 (-600.0 %, kept 100 %, 2 ms) | 326 (-781.1 %, kept 100 %, 4 ms) |

Same 40 seeded facts (seed 1414) saved through `memory_add`, then 5 `memory_search` calls.

Verdict: graymatter has the smallest save replies (280 tokens for 40 notes) and plain numbered results
that keep 100 % of the matching facts. It always returns its top 8, padding weak matches (168–266 tokens).
rtok returns only FTS matches, so its answers are smaller (71–172), but its JSON objects repeated keys and
quoting on every hit.

Added to rtok: [#1081](https://github.com/pyrlyn/rtok/pull/1081). `mem_search` now returns one line per note
(`id title: snippet`, title dropped when the snippet repeats it), 22–26 % fewer tokens on these queries
(139 → 108, 92 → 68, 172 → 134, 112 → 88, 71 → 54).
# rtok vs graymatter

Competitor: [graymatter](https://github.com/angelnicolasc/graymatter) · category: Memory · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs graymatter

**What graymatter does.** One ~10 MB Go binary (MIT): bbolt + chromem-go, MCP + Claude Code hooks, hybrid recall (vector + keyword + recency), decay, tombstones, pinning (rtok's docs at `de96106a9`, 2026-10-14).

**What rtok does here.** `memory` with lifecycle (retire, supersede, pin — T69.1), FTS5 + optional hash-embed.

### graymatter: pros
- Closest design to rtok; hybrid ranking with receipts; decay.

### graymatter: cons
- Its claimed 90 % is against full-history injection, a baseline no host uses.

### rtok: pros
- Measured 20/20 recall at N=100; integrated with the rest of rtok.

### rtok: cons
- No recency/decay ranking (T69.2 closed without a scorer).

## Verdict

**Close; rtok has the stronger measured recall on its own bench, graymatter the richer ranking.** Never cite graymatter's 83 % as rtok's.

**Gap to close:** competitor ahead on hybrid ranking and forgetting. Tracked as [T669](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims ~6 960 → ~670 tokens per session at 100 sessions ("90 %"), 83 % recall of a fact 96 sessions old, 0 % superseded | `research.md` §14 |
| rtok: FTS5 and hybrid 20/20 at N=1/10/30/100; SessionStart 100 B vs 371 866 B | `research.md` §14 T69.3 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| graymatter | no data |

<!-- competitor-bench:end -->
