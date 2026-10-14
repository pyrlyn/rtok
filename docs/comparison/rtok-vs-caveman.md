# rtok vs caveman

Competitor: [caveman](https://github.com/JuliusBrussee/caveman) · category: Compressing proxies / prompt level · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs caveman

**What caveman does.** Go tool (custom licence; engine BSL-1.1 since v2.7.0): a terse prompt mode, a shrink hook, a proxy that records and compresses, TOON, MCP compress/retrieve, and `caveman learn` that ranks token sinks.

**What rtok does here.** Native `terse` mode (162 tokens) inside the 800-token budget, `compress_prose` that keeps code fences and negations byte-identical, `toon` plugin, `report` sink ranking.

### caveman: pros
- Popular (~102 k stars), many hosts.
- `learn` ranks token sinks from history.

### caveman: cons
- Claims 65 % output shrink; JetBrains measured 8.5 % on agentic work.
- Issue #112: corrupts inline code.
- Proxy was inert on the author's machine (0 saved).

### rtok: pros
- Fixture bench: full compress 34.9 % chars / 147 tokens saved vs a caveman-lite baseline 11.7 % / 50.
- Fences and negations preserved (hard-fail tests).

### rtok: cons
- Fixture bench only; no live bill delta.

## Verdict

**rtok is better on correctness and on its fixture bench.** caveman's lever is real but small in independent measurement and its compressor has a known code-corruption bug.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Claimed 65 %; JetBrains 8.5 % on agentic work; issue #112 | `docs/comparison.md` §2; `research.md` §4 |
| rtok Full 34.9 % chars / 147 tok vs weak caveman-lite 11.7 % / 50 tok, 17 fixtures (`cargo test --test mode_bench`, 2026-09-10) | `docs/comparison.md` §2 Prompt-level table; `research.md` §3 modes subsection |
| 5 tools, ~485 description tokens | `docs/comparison.md` §2 MCP table |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `pending`. Numbers below stay pending until a real `python3 bench/competitors/run_all.py caveman` run (Colima/Docker `--cpus 4 --memory 8g`, or the manual `workflow_dispatch` job).

| side | tokens saved |
|---|---|
| rtok | pending (no run yet) |
| caveman | pending (no run yet) |

Harness: [`bench/competitors/caveman/`](../../bench/competitors/caveman/) (`run.py` on the host, `Dockerfile`, `bench.py` inside the container; shared [`bench/competitors/_lib/`](../../bench/competitors/_lib/); entry point [`run_all.py`](../../bench/competitors/run_all.py)).

<!-- competitor-bench:end -->
