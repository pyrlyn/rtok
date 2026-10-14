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

Status: `pending`. Numbers below stay pending until a real `python3 bench/competitors/run_all.py universal-ctags` run (Colima/Docker `--cpus 4 --memory 8g`, or the manual `workflow_dispatch` job).

| side | tokens saved |
|---|---|
| rtok | pending (no run yet) |
| universal-ctags | pending (no run yet) |

Harness: [`bench/competitors/universal-ctags/`](../../bench/competitors/universal-ctags/) (`run.py` on the host, `Dockerfile`, `bench.py` inside the container; shared [`bench/competitors/_lib/`](../../bench/competitors/_lib/); entry point [`run_all.py`](../../bench/competitors/run_all.py)).

<!-- competitor-bench:end -->
