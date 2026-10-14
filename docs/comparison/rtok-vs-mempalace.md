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

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| AAAK 84.2 % R@5 vs 96.6 % verbatim on LongMemEval (−12.4 pt) | `ideas.md` I-61 |
| Admission gate idea | `ideas.md` I-59, I-60 |

## Own measurements

Not measured, and no script: this cannot be benchmarked fairly under the rules of this suite (local, fixed Colima resources, no paid API keys, no invented numbers).

Why: Its admission gate needs an embeddings model; the published numbers come from LongMemEval with model calls.
