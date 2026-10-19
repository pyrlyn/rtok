#!/usr/bin/env python3
"""Build results.md / results.json / results.csv from tokens.tsv and hyperfine JSON."""
from __future__ import annotations

import csv
import json
import statistics
import sys
from pathlib import Path


def pct(xs: list[float], p: float) -> float:
    xs = sorted(xs)
    if not xs:
        return 0.0
    k = (len(xs) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def cut_pct(raw: int, value: int) -> str:
    if not raw:
        return "n/a"
    return f"{100 * (1 - value / raw):.1f} %"


def main() -> None:
    out = Path(sys.argv[1])
    competitor = sys.argv[2] if len(sys.argv) > 2 else "competitor"
    rows = list(csv.DictReader((out / "tokens.tsv").open(encoding="utf-8"), delimiter="\t"))
    md = [
        f"| case | raw tok | {competitor} tok | rtok tok | {competitor} saved | rtok saved |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    tot = {"raw": 0, "comp": 0, "rtok": 0}
    json_rows = []
    for r in rows:
        raw, comp, rt = int(r["raw"]), int(r["competitor"]), int(r["rtok"])
        tot["raw"] += raw
        tot["comp"] += comp
        tot["rtok"] += rt
        md.append(
            f"| `{r['case']}` | {raw} | {comp} | {rt} | {cut_pct(raw, comp)} | {cut_pct(raw, rt)} |"
        )
        json_rows.append(
            {
                "case": r["case"],
                "raw_tokens": raw,
                "competitor_tokens": comp,
                "rtok_tokens": rt,
                "competitor_saved_pct": None if not raw else round(100 * (1 - comp / raw), 2),
                "rtok_saved_pct": None if not raw else round(100 * (1 - rt / raw), 2),
            }
        )
    if tot["raw"]:
        md.append(
            f"| **total** | {tot['raw']} | {tot['comp']} | {tot['rtok']} | "
            f"{cut_pct(tot['raw'], tot['comp'])} | {cut_pct(tot['raw'], tot['rtok'])} |"
        )
    md += ["", "| case | tool | runs | median ms | p95 ms |", "|---|---|---:|---:|---:|"]
    latency = []
    hf_dir = out / "hyperfine"
    if hf_dir.is_dir():
        for f in sorted(hf_dir.glob("*.json")):
            payload = json.loads(f.read_text(encoding="utf-8"))
            for res in payload.get("results", []):
                times_ms = [x * 1000 for x in res["times"]]
                md.append(
                    f"| `{f.stem}` | {res['command'].split(' ', 1)[0]} | {len(times_ms)} | "
                    f"{statistics.median(times_ms):.2f} | {pct(times_ms, 0.95):.2f} |"
                )
                latency.append(
                    {
                        "case": f.stem,
                        "command": res["command"],
                        "runs": len(times_ms),
                        "median_ms": statistics.median(times_ms),
                        "p95_ms": pct(times_ms, 0.95),
                    }
                )
    summary = {
        "competitor": competitor,
        "status": "ok",
        "totals": {
            "raw_tokens": tot["raw"],
            "competitor_tokens": tot["comp"],
            "rtok_tokens": tot["rtok"],
            "competitor_saved_pct": None
            if not tot["raw"]
            else round(100 * (1 - tot["comp"] / tot["raw"]), 2),
            "rtok_saved_pct": None
            if not tot["raw"]
            else round(100 * (1 - tot["rtok"] / tot["raw"]), 2),
        },
        "cases": json_rows,
        "latency": latency,
    }
    (out / "results.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    (out / "results.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    with (out / "results.csv").open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(
            fh,
            fieldnames=[
                "case",
                "raw_tokens",
                "competitor_tokens",
                "rtok_tokens",
                "competitor_saved_pct",
                "rtok_saved_pct",
            ],
        )
        w.writeheader()
        w.writerows(json_rows)
    print((out / "results.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
