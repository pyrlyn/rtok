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

Status: `pending`. Numbers below stay pending until a real `python3 bench/competitors/run_all.py token-optimizer` run (Colima/Docker `--cpus 4 --memory 8g`, or the manual `workflow_dispatch` job).

| side | tokens saved |
|---|---|
| rtok | pending (no run yet) |
| token-optimizer | pending (no run yet) |

Harness: [`bench/competitors/token-optimizer/`](../../bench/competitors/token-optimizer/) (`run.py` on the host, `Dockerfile`, `bench.py` inside the container; shared [`bench/competitors/_lib/`](../../bench/competitors/_lib/); entry point [`run_all.py`](../../bench/competitors/run_all.py)).

<!-- competitor-bench:end -->
