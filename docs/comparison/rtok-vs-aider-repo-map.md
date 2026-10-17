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
# rtok vs aider repo map

Competitor: [aider repo map](https://aider.chat/docs/repomap.html) · category: Code graphs / repo maps · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs aider repo map

**What aider repo map does.** aider's repository map: tree-sitter tags of the repo ranked with PageRank, sent to the model as a compact file/symbol overview.

**What rtok does here.** `read` `map` mode and `graph` `outline`/`explore` (tree-sitter tags).

### aider repo map: pros
- Ranks what matters (PageRank) rather than listing everything.
- Battle-tested inside aider.

### aider repo map: cons
- Part of aider, not a standalone tool for other hosts.

### rtok: pros
- Available to 24 hosts through hooks and MCP.

### rtok: cons
- No ranking of the map by importance.

## Verdict

**Different packaging of the same idea.** aider's ranking is a good idea rtok has not adopted; rtok makes the map available outside aider.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Cited as a design alternative for `read` (aider 0.82, 2026-09-02); no numbers recorded | `research.md` §6 Plugin design surveys |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| aider-repo-map | no data |

<!-- competitor-bench:end -->
