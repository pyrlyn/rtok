# rtok vs codebase-memory-mcp 0.11.0 (release `codebase-memory-mcp-linux-arm64-portable.mcpb`, github.com/DeusData/codebase-memory-mcp)

Measured with `bench/c14/run_b3.py` in image `rtok-c15:3` (`bench/c14/b3.Dockerfile`: `rtok-c14b:2`
plus rustup 1.90.0 with `rust-analyzer`, which serena needs). rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit. Each case runs in a
fresh copy of the repo with a fresh `HOME`. Indexing and seeding happen in an untimed setup step on the same
MCP session. Then come the measured `tools/call`s: 1 warmup + 3 runs, median tokens of the text the model
receives (tiktoken `o200k_base`). Kept = share of the expected anchors literally present in the answer:
the `.rs` files from `git grep -w <sym>` for callers, item names for outlines, seeded fact ids for memory
search. "non-deterministic" means more than 2 tokens of spread across fresh runs; for codebase-memory-mcp
that comes from the temp checkout path inside its qualified names.

Tool catalog (`tools/list`, compact JSON): 17 tools, **4067 tokens** (rtok's own: 2910 tokens for 40 tools). Through `rtok mcp -- <server>`: 4067 tokens (passed through unchanged).

| Case | Raw | rtok | codebase-memory-mcp |
|---|---:|---:|---:|
| `callers:estimate` | 6003 | 1642 (72.6 %, kept 88 %, 418 ms) | 205 (96.6 %, kept 5 %, 11 ms) |
| `callers:register_mcp` | 4526 | 1428 (68.4 %, kept 72 %, 391 ms) | 1545 (65.9 %, kept 100 %, 12 ms) |
| `outline:src/plugins/json_tree/mod.rs` | 10154 | 594 (94.2 %, kept 96 %, 124 ms) | 2490 (75.5 %, kept 99 %, 12 ms) |
| `symbol:estimate` | 8699 | 424 (95.1 %, kept 100 %, 414 ms) | 310 (96.4 %, kept 100 %, 36 ms) |
| `explore:wrap foreign MCP server tools list` | — | 1810 (—, kept 0 %, 626 ms) | 2113 (—, kept 100 %, 36 ms) |

Verdict: the fastest index (sub-ms to 36 ms per query) and the most complete caller list for `register_mcp`
(100 % of files vs rtok's 72 %). It loses on ambiguous names: `trace_path estimate` returns only an
"ambiguous, pick one" stub (5 % kept), and `get_code_snippet estimate` needs a second call (both counted).
Its file outline is 4× rtok's size because every row carries a fully qualified name. It is the only tool
that found `src/mcp/wrap.rs` for the natural-language explore query.

What rtok should borrow:
- an `ambiguous` answer that lists the candidates' paths so the next call can pick one (rtok prints `?`);
- ranked full-text search over symbol names and paths for natural-language queries (rtok's `explore`
  only resolves identifiers that literally appear in the query).
