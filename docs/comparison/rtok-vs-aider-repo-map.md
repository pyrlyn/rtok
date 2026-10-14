# rtok vs aider repo map (`aider-chat` 0.86.2, github.com/Aider-AI/aider)

Measured with `bench/c14` (image `rtok-c14:12ebeb9`): rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13` (main, 2026-10-14) and run on the same commit
of this repository as the input. Each tool: 1 warmup + 3 runs, fresh `HOME`/XDG dirs and a
fresh copy of the repo per run, same argv order, tokens counted with tiktoken
`o200k_base` 0.12.0 on stdout. Fixtures are generated with seed 1414. Docker: 4 CPU, 8 GiB.
Rerun: `docker build -f bench/c14/Dockerfile -t rtok-c14 .` then
`python3 bench/c14/run_c14.py --repo /repo --out /out` inside the container.
Savings = 1 − tool/raw. "Keeps" = share of distinct JSON keys and values that still appear in
the output (a lossless encoder keeps 100 %).

`aider --show-repo-map --map-tokens N --model gpt-4o` (no API key needed for the map).

| Case | Raw (all `src/**/*.rs`) | rtok | aider | aider saves |
|---|---:|---:|---:|---:|
| repo map, budget 1024 | 1465404 | no equivalent | 2397 (2394–2462) | 99.8 % |
| repo map, budget 4096 | 1465404 | no equivalent | 7779 (7761–7845) | 99.5 % |

aider's map is not deterministic: three fresh runs gave different token counts (the ranking
ties change between runs), so the median and range are reported. The count includes aider's
start-up banner (about 80 tokens). aider overshoots its own budget by about 2.3× and 1.9×.

What rtok needs: a token-budgeted repo map (PageRank over the `graph` definition/reference
edges, the top files with signature lines, within an exact budget, deterministic). Not done yet.
