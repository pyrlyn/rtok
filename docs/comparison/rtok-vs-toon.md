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
