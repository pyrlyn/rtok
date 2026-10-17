# rtok vs toon (`@toon-format/cli` 4.4.0, github.com/toon-format/toon)

Measured with `bench/c14` (image `rtok-c14:12ebeb9`): rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13` (main, 2026-10-14) and run on the same commit
of this repository as the input. Each tool: 1 warmup + 3 runs, fresh `HOME`/XDG dirs and a
fresh copy of the repo per run, same argv order, tokens counted with tiktoken
`o200k_base` 0.12.0 on stdout. Fixtures are generated with seed 1414. Docker: 4 CPU, 8 GiB.
Rerun: `docker build -f bench/c14/Dockerfile -t rtok-c14 .` then
`python3 bench/c14/run_c14.py --repo /repo --out /out` inside the container.
Savings = 1 − tool/raw. "Keeps" = share of distinct JSON keys and values that still appear in
the output (a lossless encoder keeps 100 %).

toon is a lossless JSON encoder: uniform arrays become one header plus CSV-like rows,
objects become indented YAML-like blocks. rtok's `cmd` filter (`rtok filter --cmd "cat x.json"`)
compacts JSON lossily: the first N array items as `{k: v}` lines, `… +K more`, and an
`rtok expand <id>` trailer to the archived original.

| Case | Raw | rtok | toon | rtok saves | toon saves | rtok keeps | toon keeps |
|---|---:|---:|---:|---:|---:|---:|---:|
| 300-row uniform table | 15302 | 808 | 5110 | 94.7 % | 66.6 % | 11 % | 100 % |
| nested API page (120 items) | 11414 | 1338 | 7570 | 88.3 % | 33.7 % | 34 % | 100 % |
| mixed array (200 items) | 3906 | 349 | 2687 | 91.1 % | 31.2 % | 23 % | 100 % |
| deep config (40 routes) | 976 | 736 | 403 | 24.6 % | 58.7 % | 100 % | 100 % |
| real `web/package.json` | 613 | 610 | 566 | 0.5 % | 7.7 % | 100 % | 100 % |

Verdict: the numbers are not like for like. rtok wins on large arrays only because it drops
rows (they stay one `rtok expand` away). Where both keep everything (deep config,
package.json), toon is smaller: it prints repeated keys once (`limits[40:]{rps,burst}:`)
and drops quotes and braces, while rtok prints `{burst: 20, rps: 100}` per entry.

What rtok needs:
- TOON tables in the `cmd` JSON path (rtok already has a `toon` encoder, but only on the
  proxy and MCP surfaces). Draft PR: branch `feat/cmd-json-toon-table`: the same shown rows
  as one table, kept only when shorter.
- Tabular encoding of uniform object maps (`limits.routeN: {rps, burst}`) and unquoted strings
  in the lossless path. Not done yet.
# rtok vs TOON

> **TOON is not a competitor of rtok because rtok already applies the TOON format where it wins; it is a dependency/format, not an alternative stack.**


Competitor: [TOON](https://github.com/toon-format/toon) · category: Formats · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs TOON

**What TOON does.** A compact tabular format for JSON arrays (TypeScript library, MIT).

**What rtok does here.** `toon` plugin, on by default, rewrites a block only when TOON is smaller.

### TOON: pros
- Real saving on tabular JSON.

### TOON: cons
- Most agent payloads are not tables.

### rtok: pros
- Applies it only where it wins.

### rtok: cons
- —

## Verdict

**Complementary: rtok uses the format.**

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| tabular JSON tokens | applies TOON only when smaller (no separate % in docs) | −42.6 % tokens (vendor bench) | `docs/comparison.md` §2; `research.md` §4, §7 |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| −42.6 % tokens on tabular JSON; accuracy 72.2 vs 71.4 (vendor bench, survived fact-check) | `docs/comparison.md` §2 Formats; `research.md` §4, §7 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| toon | no data |

<!-- competitor-bench:end -->
