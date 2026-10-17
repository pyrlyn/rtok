# rtok vs caveman v4.0.1 (github.com/JuliusBrussee/caveman)

caveman's headline saving, the terse "caveman" reply style, is a prompt/skill. It changes what a model
writes and needs a live model, so it can't be measured deterministically and is not benchmarked here.
Its deterministic tool is `caveman-shrink` (built from the v4.0.1 tag with Go 1.26.9). It shrinks MCP tool
catalogs: it drops examples and titles and shortens long descriptions, but keeps names, params and enums.

Measured with `bench/c14/run_b2_catalog.py`. The catalogs are the `tools/list` result of real servers in
the image (lean-ctx 3.11.4 in its default profile, engram 3.4.0) plus caveman's own test catalog,
serialised compactly. The rtok number is the catalog as seen through `rtok mcp -- <server>`. caveman-shrink
ran 3 times with a fresh `HOME` and `CAVEMAN_CCR_DB` and gave identical output each time. Tokens: tiktoken `o200k_base`.

| Catalog | Tools | Raw | rtok (`rtok mcp -- <server>`) | caveman-shrink | caveman saves |
|---|---:|---:|---:|---:|---:|
| lean-ctx | 12 | 2513 | 2513 | 2207 | 12.2 % |
| engram | 23 | 5822 | 5822 | 5213 | 10.5 % |
| caveman testdata | 2 | 279 | — | 197 | 29.4 % |

Verdict: rtok's MCP wrapper passes foreign catalogs through unchanged (by design: "wrap losslessly"), so
caveman-shrink wins by 10–29 %. Its description cut is lossy, and its README says so.

What rtok needs: an opt-in catalog compaction in `rtok mcp --` (drop `title`/`examples`, cap long
descriptions, keep the schema structure). Not done yet.
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

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| caveman | no data |

<!-- competitor-bench:end -->
