# rtok vs lean-ctx

Competitor: [lean-ctx](https://github.com/yvgude/lean-ctx) · category: Read, search and shell MCP servers · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs lean-ctx

**What lean-ctx does.** Rust MCP server plus hooks (Apache-2.0) for bounded file reads (10 read modes), search, a shell wrapper with 95+ patterns, deduplicated re-reads (~13 tokens) and handoff tools; it also denies native Grep/Glob.

**What rtok does here.** `read` plugin: 3 MCP tools, 6 read modes (`full`, `lines`, `map`, `signatures`, `diff`, `stripped`), sha256 dedup, root guard, no banner; the injection budget is 800 tokens per turn, byte-stable.

### lean-ctx: pros
- More read modes (`density`, a mode predictor learned from past sessions).
- Sub-agent handoff tools (`ctx_handoff`, `ctx_agent`).
- Re-read of an unchanged file costs ~13 tokens.

### lean-ctx: cons
- ~3.0–3.1 K tokens of banner and instructions injected per turn, cached or not.
- 12 tools ≈ 697 description tokens every turn on the author's machine.
- Measured 0 output saved on the author's machine.

### rtok: pros
- Fixed cost per turn ~10× smaller (whole budget 800 tokens; ~223 description tokens for 12 tools).
- Delta reads (T58.1) and measurement per call.

### rtok: cons
- Fewer read modes; no learned mode predictor.

## Verdict

**rtok is better on this workload.** The per-turn fixed cost of lean-ctx is larger than what its read modes save here; rtok copies the read modes that paid off and keeps the per-turn cost small.

**Gap to close:** competitor ahead on more read modes and a learned mode predictor. Tracked as [T670](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| output tokens saved (author machine) | no data on same workload | 0 output saved | `research.md` §4 matrix |
| fixed per-turn inject / description tokens | ~800 token budget; ~223 description tokens / 12 tools | ~3.0–3.1 K tokens/turn; ~697 description tokens / 12 tools | `docs/comparison.md` §2; `research.md` §9.1 |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| lean-ctx injects ~3.1 K tokens per turn; 0 output saved | `research.md` §4 matrix; `docs/comparison.md` §2 |
| 12 tools, ~697 description tokens per turn (`rtok doctor`, 2026-09-09) | `docs/comparison.md` §2 MCP table |
| v3.10.2: 30+ tools, ~13-token re-reads, ~3.0 K fixed per-session overhead (own README) | `research.md` §9.1 |
| lean-ctx ≈ 27 % of the tool table in the T59.4 re-measure (885 sessions) | `research.md` §9.3 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| lean-ctx | no data |

<!-- competitor-bench:end -->
