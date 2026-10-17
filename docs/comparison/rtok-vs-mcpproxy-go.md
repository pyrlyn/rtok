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
