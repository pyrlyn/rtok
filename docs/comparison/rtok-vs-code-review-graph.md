# rtok vs code-review-graph (PyPI 2.3.9, github.com/tirth8205/code-review-graph), measured with `bench/c14`

Measured with `bench/c14` (image `rtok-c14:12ebeb9`): rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13` (main, 2026-10-14) and run on the same commit
of this repository as the input. Each tool: 1 warmup + 3 runs, fresh `HOME`/XDG dirs and a
fresh copy of the repo per run, same argv order, tokens counted with tiktoken
`o200k_base` 0.12.0 on stdout. Fixtures are generated with seed 1414. Docker: 4 CPU, 8 GiB.
Rerun: `docker build -f bench/c14/Dockerfile -t rtok-c14 .` then
`python3 bench/c14/run_c14.py --repo /repo --out /out` inside the container.
Savings = 1 − tool/raw. "Keeps" = share of distinct JSON keys and values that still appear in
the output (a lossless encoder keeps 100 %).

Both indexes are built in an untimed setup step on every run (`rtok graph index`,
`code-review-graph build -q`).

| Case | Raw | rtok | crg | rtok saves | crg saves |
|---|---:|---:|---:|---:|---:|
| review of `HEAD~5..HEAD` (raw = `git diff`) | 30619 | 30181 (filtered diff) | 215 (`detect-changes --brief`) / 728727 (JSON) | 1.4 % | 99.3 % / −2280 % |
| callers of `estimate` (raw = `git grep -n -w`) | 6003 | 1337 | 4180 | 77.7 % | 30.4 % |
| callers of `register_mcp` | 4526 | 789 | 4174 | 82.6 % | 7.8 % |
| dead code, whole repo | — | 1938 | 17580 | — | — |

Notes:
- Review: crg `--brief` is a risk summary (changed functions, affected flows, untested
  callers, risk score), not the diff. It is the case crg itself advertises. rtok has no such summary.
  Its `git diff` filter keeps the diff almost whole. The full crg JSON is 24× larger than the diff.
- Callers: both names are defined many times. rtok answers with caller rows marked `?`
  (ambiguous). crg returns a list of 46–55 candidates to disambiguate and no callers. rtok is
  smaller and more useful.
- Dead code: the two lists have different scope (rtok: private definitions, all languages,
  capped. crg: 943 functions/classes, uncapped), so only absolute size is shown. crg took 88 s
  per run, rtok 1.5 s.

What rtok needs: a `review`/change-impact summary for a diff range (changed symbols,
affected callers/tests from `graph affected`, untested changes) as a compact block. Not done yet.
# rtok vs code-review-graph

Competitor: [code-review-graph](https://github.com/tirth8205/code-review-graph) · category: Code graphs · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs code-review-graph

**What code-review-graph does.** Python MCP server (MIT): tree-sitter graph in SQLite, impact radius, minimal context, communities; 30 tools; optional embeddings.

**What rtok does here.** `graph` with `impact` and `graph_diff`, 7 operations in 165 description tokens.

### code-review-graph: pros
- Genuinely clever impact analysis.
- Rich review-oriented operations.

### code-review-graph: cons
- 30 tools ≈ 2 295 description tokens every turn — the most expensive server measured.
- F1 0.69 against its own edges (own README).

### rtok: pros
- ~14× smaller tool bill; Rust, hook-fast.

### rtok: cons
- Less review-specific tooling (communities).

## Verdict

**rtok is better on cost; code-review-graph has richer review features.**

**Gap to close:** competitor ahead on richer review/blast-radius features (~30 MCP tools). Tracked as [T664](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 30 tools, ~2 295 description tokens | `docs/comparison.md` §2 MCP table |
| Claims 65× median (36–376×, 6 repos); F1 0.69 vs own edges | `research.md` §4 matrix |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| code-review-graph | no data |

<!-- competitor-bench:end -->
