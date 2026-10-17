# rtok vs codegraph 1.6.2 (release `codegraph-linux-arm64.tar.gz`, github.com/colbymchenry/codegraph)

Measured with `bench/c14/run_b3.py` in image `rtok-c15:3` (`bench/c14/b3.Dockerfile`: `rtok-c14b:2`
plus rustup 1.90.0 with `rust-analyzer`, which serena needs). rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit. Each case runs in a
fresh copy of the repo with a fresh `HOME`. Indexing and seeding happen in an untimed setup step on the same
MCP session. Then come the measured `tools/call`s: 1 warmup + 3 runs, median tokens of the text the model
receives (tiktoken `o200k_base`). Kept = share of the expected anchors literally present in the answer:
the `.rs` files from `git grep -w <sym>` for callers, item names for outlines, seeded fact ids for memory
search. "non-deterministic" means more than 2 tokens of spread across fresh runs; for codebase-memory-mcp
that comes from the temp checkout path inside its qualified names.

Tool catalog (`tools/list`, compact JSON): 1 tools, **402 tokens** (rtok's own: 2910 tokens for 40 tools). Through `rtok mcp -- <server>`: 402 tokens (passed through unchanged).

| Case | Raw | rtok | codegraph |
|---|---:|---:|---:|
| `callers:estimate` | 6003 | 1642 (72.6 %, kept 88 %, 418 ms) | 456 (92.4 %, kept 35 %, 110 ms) |
| `callers:register_mcp` | 4526 | 1428 (68.4 %, kept 72 %, 391 ms) | 2425 (46.4 %, kept 97 %, 128 ms) |
| `outline:src/plugins/json_tree/mod.rs` | 10154 | 594 (94.2 %, kept 96 %, 124 ms) | n/a |
| `symbol:estimate` | 8699 | 424 (95.1 %, kept 100 %, 414 ms) | 1149 (86.8 %, kept 100 %, 137 ms) |
| `explore:wrap foreign MCP server tools list` | — | 1810 (—, kept 0 %, 626 ms) | 6544 (—, kept 0 %, 394 ms) |

codegraph's MCP surface is one tool, `codegraph_explore`, with a 402-token catalog (the smallest here).
Callers and symbol lookups go through its CLI (`codegraph callers`, `codegraph node`), measured as such;
it has no outline command.

Verdict: `callers` splits the answer per definition and caps it, which keeps the answer small (456 tokens)
but drops most files for `estimate` (35 % kept); for `register_mcp` it keeps 97 %. `node estimate` returns all
5 definitions in full (1149 tokens vs rtok 424). `codegraph_explore` is a large, one-shot dump (6544
tokens) and still misses `src/mcp/wrap.rs` for this query.

What rtok should borrow: the one-tool MCP surface idea. A 400-token catalog that routes everything through
`explore` is 7× cheaper than rtok's 40-tool catalog for sessions that only need code search. A
`[mcp] tools = "code"` profile would cover it.
# rtok vs codegraph

Competitor: [codegraph](https://github.com/colbymchenry/codegraph) · category: Code graphs · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs codegraph

**What codegraph does.** Code graph in `.codegraph/codegraph.db` (SQLite + FTS5) with one `codegraph_explore` MCP tool and a CLI (MIT).

**What rtok does here.** `graph` plugin, SQLite only, 7 operations in 165 description tokens.

### codegraph: pros
- One tool, small description cost.
- Published multi-repo claim (−88 % tool calls, −62 % tokens).

### codegraph: cons
- 97 MB `.codegraph/` for one repo (cross-code).
- Claims are vendor numbers.

### rtok: pros
- Impact, callers and diff operations; hook-path latency budget.

### rtok: cons
- No published with/without live bench yet.

## Verdict

**Close; no shared measurement.** Similar design (SQLite, few tools). rtok's index is smaller per the author's checks, codegraph has a published (vendor) with/without claim.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims −88 % tool calls, −62 % tokens over 7 repos (README, confirmed as stated) | `research.md` §4 matrix, §7 |
| 97 MB `.codegraph/` for one repo | `research.md` §4 matrix |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| codegraph | no data |

<!-- competitor-bench:end -->
