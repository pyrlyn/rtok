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
# rtok vs codebase-memory-mcp

Competitor: [codebase-memory-mcp](https://github.com/DeusData/codebase-memory-mcp) · category: Code graphs · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs codebase-memory-mcp

**What codebase-memory-mcp does.** C MCP server (MIT): tree-sitter (158 grammars) + hybrid LSP (11 languages) into SQLite (zstd, later LZ4), 15 tools, Cypher-like queries; paper arXiv:2603.27277.

**What rtok does here.** `graph`: tree-sitter tags, SQLite, 7 operations.

### codebase-memory-mcp: pros
- Huge grammar coverage and hybrid LSP precision.
- Published paper (31 repos, 83 % answer quality).

### codebase-memory-mcp: cons
- Exited on start on the author's machine (0 tools, 2026-09-02).
- 281 MB binary, 141 MB cache.

### rtok: pros
- Small binary (18.9 MiB), works on the author's machine.

### rtok: cons
- Fewer grammars; no LSP backend yet (v0.2).

## Verdict

**codebase-memory-mcp is broader; rtok is lighter and was the one that ran here.**

**Gap to close:** competitor ahead on broader Cypher-style graph queries. Tracked as [T665](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims 99.2 % fewer tokens on 5 queries; Linux kernel in 3 min | `research.md` §4 matrix |
| v0.10.0, arXiv:2603.27277, 31 repos, 83 % answer quality | `research.md` §9.1 |
| 281 MB binary, 141 MB cache; exits on start here | `research.md` §4 matrix |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| codebase-memory-mcp | no data |

<!-- competitor-bench:end -->
