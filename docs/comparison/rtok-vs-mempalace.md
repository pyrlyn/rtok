# rtok vs MemPalace

Competitor: [MemPalace](https://github.com/MemPalace/mempalace) · category: Memory · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs MemPalace

**What MemPalace does.** Memory system with an admission checkpoint (`dedup_threshold` 0.9 over embeddings), temporal validity windows and the lossy AAAK notation.

**What rtok does here.** `memory`: verbatim notes (D4), import dedup by sha256, upsert by title.

### MemPalace: pros
- Duplicate check on save; temporal validity.

### MemPalace: cons
- AAAK costs 12.4 points of recall by its own benchmark.

### rtok: pros
- Verbatim storage, no recall loss from abbreviation.

### rtok: cons
- `mem_save` has no embedding-based admission gate (I-59).

## Verdict

**rtok is better on recall fidelity; MemPalace has the admission gate rtok lacks.**

**Gap to close:** competitor ahead on memory admission gate with embedding dedup. Tracked as [T671](../../plan.md) — make rtok beat this.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| AAAK 84.2 % R@5 vs 96.6 % verbatim on LongMemEval (−12.4 pt) | `ideas.md` I-61 |
| Admission gate idea | `ideas.md` I-59, I-60 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: Embedding-based admission; needs model/API.

| side | tokens saved |
|---|---|
| rtok | no data |
| mempalace | no data |

<!-- competitor-bench:end -->
