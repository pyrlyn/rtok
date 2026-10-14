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
