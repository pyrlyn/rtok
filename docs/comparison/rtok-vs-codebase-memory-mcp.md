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

Status: `pending`. Numbers below stay pending until a real `python3 bench/competitors/run_all.py codebase-memory-mcp` run (Colima/Docker `--cpus 4 --memory 8g`, or the manual `workflow_dispatch` job).

| side | tokens saved |
|---|---|
| rtok | pending (no run yet) |
| codebase-memory-mcp | pending (no run yet) |

Harness: [`bench/competitors/codebase-memory-mcp/`](../../bench/competitors/codebase-memory-mcp/) (`run.py` on the host, `Dockerfile`, `bench.py` inside the container; shared [`bench/competitors/_lib/`](../../bench/competitors/_lib/); entry point [`run_all.py`](../../bench/competitors/run_all.py)).

<!-- competitor-bench:end -->
