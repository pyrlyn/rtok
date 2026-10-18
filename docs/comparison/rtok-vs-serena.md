# rtok vs serena-agent 2.0.0 (PyPI `serena-agent`, LSP backend rust-analyzer 1.90.0), measured with `bench/c14/run_b3.py`

Measured with `bench/c14/run_b3.py` in image `rtok-c15:3` (`bench/c14/b3.Dockerfile`: `rtok-c14b:2`
plus rustup 1.90.0 with `rust-analyzer`, which serena needs). rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit. Each case runs in a
fresh copy of the repo with a fresh `HOME`. Indexing and seeding happen in an untimed setup step on the same
MCP session. Then come the measured `tools/call`s: 1 warmup + 3 runs, median tokens of the text the model
receives (tiktoken `o200k_base`). Kept = share of the expected anchors literally present in the answer:
the `.rs` files from `git grep -w <sym>` for callers, item names for outlines, seeded fact ids for memory
search. "non-deterministic" means more than 2 tokens of spread across fresh runs; for codebase-memory-mcp
that comes from the temp checkout path inside its qualified names.

Tool catalog (`tools/list`, compact JSON): 21 tools, **5612 tokens** (rtok's own: 2910 tokens for 40 tools). Through `rtok mcp -- <server>`: 5612 tokens (passed through unchanged).

| Case | Raw | rtok | serena |
|---|---:|---:|---:|
| `callers:estimate` | 6003 | 1642 (72.6 %, kept 88 %, 418 ms) | 6601 (-10.0 %, kept 35 %, 21615 ms) |
| `callers:register_mcp` | 4526 | 1428 (68.4 %, kept 72 %, 391 ms) | 508 (88.8 %, kept 3 %, 20318 ms) |
| `outline:src/plugins/json_tree/mod.rs` | 10154 | 594 (94.2 %, kept 96 %, 124 ms) | 203 (98.0 %, kept 59 %, 1273 ms) |
| `symbol:estimate` | 8699 | 424 (95.1 %, kept 100 %, 414 ms) | 755 (91.3 %, kept 100 %, 4281 ms) |
| `explore:wrap foreign MCP server tools list` | — | 1810 (—, kept 0 %, 626 ms) | n/a |

serena's default `claude-code` context exposes 2 tools (`initial_instructions`, `serena_repl`, 1032 tokens).
Measured with `--agent-interface tools` (21 tools, 5612 tokens) so each operation is one call.
rust-analyzer starts cold in every fresh run, so the first semantic call takes 4–22 s.

Verdict: serena is exact rather than textual. `find_referencing_symbols` resolves one definition, so for
`register_mcp` (32 definitions) it reports only the callers of `claude::register_mcp` (3 % of the grep files,
which is correct for that symbol). For `estimate` it returns 6601 tokens of `content_around_reference`.
Its outline is the smallest (203 tokens) but groups names by kind without lines, keeping 59 % of item names.

What rtok should borrow: nothing for token cost. rtok already offers `backend = "lsp"` for exact
references. The kind-grouped outline (`{"Function": [...]}`) is worth a look as a terse mode.
# rtok vs serena

Competitor: [serena](https://github.com/oraios/serena) · category: Code graphs · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs serena

**What serena does.** Python MCP server (MIT) with LSP-backed symbol tools for 40+ languages (v1.6.0): find symbol, references, replace symbol body.

**What rtok does here.** `graph` plugin: tree-sitter tags into SQLite; 7 operations (`symbol`, `callers`, `impact`, `outline`, `explore`, `graph_diff`, `graph_export`) in 165 description tokens; hook ≤ 10 ms.

### serena: pros
- Most precise symbol resolution available (LSP).
- Edit tools at symbol level.

### serena: cons
- 22 tools ≈ 1 494 description tokens every turn.
- Timed out at 30 s on the author's machine.

### rtok: pros
- ~9× smaller tool bill; fast SQLite index.
- Definition recall/precision 1.000 on a 30-symbol set.

### rtok: cons
- Reference recall 0.351 originally (0.924 after T387), still below LSP-grade.

## Verdict

**serena is better at precision; rtok is better on cost.** Use serena when exact references matter more than ~1.5 K tokens per turn (`docs/comparison.md` §7).

**Gap to close:** competitor ahead on LSP-grade symbol/reference precision. Tracked as [T663](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| tool description tokens / turn | ~165 description tokens (7 ops) | ~1 494 description tokens (22 tools) | `docs/comparison.md` §2 MCP table |
| reference recall | 0.351 → 0.924 after T387 | LSP-grade (no numeric in rtok docs) | `docs/comparison.md` §5; `research.md` §9.3 |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 22 tools, ~1 494 description tokens | `docs/comparison.md` §2 MCP table |
| Times out at 30 s here; most precise | `research.md` §4 matrix |
| rtok reference recall 0.351 → 0.924 after T387 | `docs/comparison.md` §5; `research.md` §9.3 Code graph row |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| serena | no data |

<!-- competitor-bench:end -->
