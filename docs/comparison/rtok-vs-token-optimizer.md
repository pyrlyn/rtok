# rtok vs token-optimizer

Competitor: [token-optimizer](https://github.com/alexgreensh/token-optimizer) · category: Read, search and shell hooks · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs token-optimizer

**What token-optimizer does.** A pile of Python hooks (27 on the author's machine): delta reads, structure maps, bash compression for 111 commands, archive of results over 4 KB, compaction checkpoints and quality nudges. Licence: PolyForm Noncommercial 1.0.0 (local LICENSE).

**What rtok does here.** One Rust binary, 8 hook entries across 7 events; `read`, `cmd`, `archive`, `inject` plugins cover the same jobs; checkpoints at `PreCompact`.

### token-optimizer: pros
- Broad feature set, incl. coaching nudges and archive with expand.
- Lossless archive.

### token-optimizer: cons
- Python subprocess per hook; a full event chain fires up to ~30 subprocesses on the author's machine.
- Non-commercial licence.
- Only the author's own meter ("$313/mo").

### rtok: pros
- One process, p95 8.25 ms per hook; fail-open.
- One ledger.
- Permissive licence.

### rtok: cons
- token-optimizer's coaching nudges have no direct rtok equivalent beyond modes (T53.1 A/B).

## Verdict

**rtok is better.** Same ideas, but one fast process instead of a Python hook chain, and a measured ledger instead of a self-reported dollar figure.

## Savings (both sides)

Sourced figures only. If a side has no number for that case, the cell is `no data` — never invented.

| Case | rtok saved | competitor saved | Source |
|---|---|---|---|
| (no paired sourced savings in rtok docs for this tool) | no data | no data | — |

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 27 Python hooks; up to ~30 subprocesses per event chain | `docs/comparison.md` §2 and §3 |
| Claimed "$313/mo"; author's own meter only | `research.md` §4 matrix |
| rtok hook p95 8.25 ms | `docs/comparison.md` §3 |

## Own measurements

Numbers from the Python harness are **pending until a real run**. Do not treat empty cells as zeros.

<!-- competitor-bench:begin -->

### Harness results (auto-generated)

Status: `skipped`. Numbers below are from the last `bench/competitors/run_all.py` run; until a real run exists they stay pending.

Skipped: harness not implemented: image has no real install of the tool and the light bench only tokenizes its --help/stub output, so numbers would be meaningless

| side | tokens saved |
|---|---|
| rtok | no data |
| token-optimizer | no data |

<!-- competitor-bench:end -->
