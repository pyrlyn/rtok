# rtok vs universal-ctags (Debian trixie `universal-ctags` 5.9.0)

Measured with `bench/c14` (image `rtok-c14:12ebeb9`): rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13` (main, 2026-10-14) and run on the same commit
of this repository as the input. Each tool: 1 warmup + 3 runs, fresh `HOME`/XDG dirs and a
fresh copy of the repo per run, same argv order, tokens counted with tiktoken
`o200k_base` 0.12.0 on stdout. Fixtures are generated with seed 1414. Docker: 4 CPU, 8 GiB.
Rerun: `docker build -f bench/c14/Dockerfile -t rtok-c14 .` then
`python3 bench/c14/run_c14.py --repo /repo --out /out` inside the container.
Savings = 1 − tool/raw. "Keeps" = share of distinct JSON keys and values that still appear in
the output (a lossless encoder keeps 100 %).

| Case | Raw | rtok | ctags | rtok saves | ctags saves |
|---|---:|---:|---:|---:|---:|
| outline of `src/plugins/json_tree/mod.rs` | 10154 | 656 | 617 | 93.5 % | 93.9 % |
| symbol table of all `src/**/*.rs` | 1465404 | not measured | 156628 | — | 89.3 % |

Notes:
- Outline: the rtok output is the `cat` filter's head/tail cut of the file, not an outline. ctags
  (`-x --_xformat="%N %K %n"`) lists every symbol with kind and line. At equal size ctags gives
  a full map of the file, rtok gives the first lines only. ctags is better here.
- Tree index: `rtok graph index` writes definitions into SQLite and has no text listing, so there is
  no rtok output to count. ctags is far too big to give an agent whole (156k tokens).

What rtok needs: a `rtok graph outline <file>` (name kind line from the existing index) and the
`cat` filter emitting that outline for large source files instead of a head cut. Not done yet.
# rtok vs Universal Ctags

Competitor: [Universal Ctags](https://github.com/universal-ctags/ctags) · category: Code graphs / tags · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs Universal Ctags

**What Universal Ctags does.** The classic tag generator: cheap symbol definitions for hundreds of languages, no call graph.

**What rtok does here.** `graph`: tree-sitter tags plus references, callers and impact in SQLite.

### Universal Ctags: pros
- Fast, tiny, ubiquitous, huge language coverage.

### Universal Ctags: cons
- Definitions only; no references or call graph; no agent surface.

### rtok: pros
- References, callers, impact, MCP surface.

### rtok: cons
- Fewer languages.

## Verdict

**Different tools; rtok answers more questions, ctags is the cheaper baseline.**

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Cited as a design alternative for `graph` (6.x, 2026-09-02); no numbers recorded | `research.md` §6 Plugin design surveys |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| universal-ctags | no data |

<!-- competitor-bench:end -->
