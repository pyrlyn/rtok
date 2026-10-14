# rtok vs TOON

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

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| −42.6 % tokens on tabular JSON; accuracy 72.2 vs 71.4 (vendor bench, survived fact-check) | `docs/comparison.md` §2 Formats; `research.md` §4, §7 |

## Own measurements

Not measured. No script in this pass.

Feasible later: Token counts on a fixed JSON corpus are LLM-free; not written in this pass.
