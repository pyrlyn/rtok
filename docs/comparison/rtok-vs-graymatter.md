# rtok vs graymatter 0.20.1 (release `graymatter_0.20.1_linux_arm64.tar.gz`, github.com/angelnicolasc/graymatter)

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
