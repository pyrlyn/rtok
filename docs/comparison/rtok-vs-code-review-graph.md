# rtok vs code-review-graph (PyPI 2.3.9, github.com/tirth8205/code-review-graph)

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
