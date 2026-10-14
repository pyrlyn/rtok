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

**Not ported; rtok's approach is better for its scope.** The honest saving (17–43 % on description bytes) is a slice of a 6.2 % slice, and host-native Tool Search covers much of it.

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
