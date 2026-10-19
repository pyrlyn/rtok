# rtok vs mcpproxy-go 0.70.0 (release `mcpproxy-0.70.0-linux-arm64.tar.gz`, github.com/smart-mcp-proxy/mcpproxy-go)

Measured with `bench/c14/run_b3.py` in image `rtok-c15:3` (`bench/c14/b3.Dockerfile`: `rtok-c14b:2`
plus rustup 1.90.0 with `rust-analyzer`, which serena needs). rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit. Each case runs in a
fresh copy of the repo with a fresh `HOME`. Indexing and seeding happen in an untimed setup step on the same
MCP session. Then come the measured `tools/call`s: 1 warmup + 3 runs, median tokens of the text the model
receives (tiktoken `o200k_base`). Kept = share of the expected anchors literally present in the answer:
the `.rs` files from `git grep -w <sym>` for callers, item names for outlines, seeded fact ids for memory
search. "non-deterministic" means more than 2 tokens of spread across fresh runs; for codebase-memory-mcp
that comes from the temp checkout path inside its qualified names.

mcpproxy-go fronts upstream MCP servers. Instead of their tools it exposes 12 of its own
(`retrieve_tools` for BM25 tool search, `call_tool_read|write|destructive`, …). It promises fewer tokens
than loading every upstream catalog. Measured with engram 3.4.0 (23 tools) as the only upstream, stdio
transport (`serve --listen ""`), `quarantined: false` (new upstreams are quarantined by default and expose
nothing). Column = tokens the client holds before its first real call.

| Setup | Tokens | vs engram direct |
|---|---:|---:|
| engram direct (tools/list) | 5822 | 0.0 % |
| rtok mcp -- engram (tools/list) | 5822 | 0.0 % |
| mcpproxy tools/list | 7065 | +21.4 % |
| mcpproxy tools/list + retrieve_tools | 9416 | +61.7 % |
| mcpproxy (compact) tools/list + retrieve_tools | 7687 | +32.0 % |

Tool catalog (`tools/list`, compact JSON): 12 tools, **7055 tokens** (rtok's own: 2910 tokens for 40 tools). Through `rtok mcp -- <server>`: 7055 tokens (passed through unchanged).

Verdict: with one mid-sized upstream the proxy costs more than the server it hides. Its own catalog
(7065) is already larger than engram's (5822). One `retrieve_tools` adds about 2350 tokens (full mode) or
620 (`--tool-response-mode compact`). The break-even is several large upstreams. rtok's wrapper is lossless
and adds 0 tokens.

What rtok should borrow: lazy tool discovery for wrapped servers. `rtok mcp --` could expose a 2-tool
surface (search + call) when the wrapped catalog exceeds a token budget. Combined with the catalog
compaction from the caveman report, that covers the real use case. Not done yet.
# rtok vs mcpproxy-go

Competitor: [mcpproxy-go](https://github.com/smart-mcp-proxy/mcpproxy-go) · category: Compressing proxies · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs mcpproxy-go

**What mcpproxy-go does.** A Go MCP proxy that hides many tools behind a `retrieve_tools` search (and a `code_execution` mode) to shrink tool descriptions.

**What rtok does here.** Small native tool list (~223 tokens), `doctor` prices every server, `proxy.tools_rewrite` (off by default) after T59.5 measured tool descriptions at 6.2 % of session input.

### mcpproxy-go: pros
- Measured, LLM-free bench in its own repo.
- Works over any set of MCP servers.

### mcpproxy-go: cons
- README claims ~99 % reduction; its own bench measures 17 % / 43 %, and its own design note says the 99 % cannot stand once its own tools are counted.

### rtok: pros
- Host Tool Search already defers schemas on Claude Code; rtok flags when it is off.

### rtok: cons
- No generic tool-search proxy for foreign servers.

## Verdict

**Not ported; rtok's approach is better for its scope.** The honest saving (17–43 % on description bytes) is a slice of a 6.2 % slice, and host-native Tool Search covers much of it (rtok's docs at `de96106a9`, 2026-10-14).

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claims ~99 %; bench 17 % (`retrieve_tools`, 1730 → 1431) and 43 % (`code_execution`, 1730 → 986) on a 45-tool corpus; design note retracts the 99 % | `research.md` §7 (2026-10-08 entry) |
| Tool descriptions × turns = 6.2 % of session input (T59.5) | `research.md` §9.3 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| mcpproxy-go | no data |

<!-- competitor-bench:end -->
