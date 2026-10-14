#!/usr/bin/env python3
"""Turn tokens.tsv and hyperfine JSON exports into results.md (median and p95, ms)."""
import csv, json, pathlib, statistics, sys

out = pathlib.Path(sys.argv[1])

def pct(xs, p):
    xs = sorted(xs)
    k = (len(xs) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)

rows = list(csv.DictReader(open(out / "tokens.tsv"), delimiter="\t"))
lines = ["| case | raw tok | competitor tok | rtok tok | competitor cut | rtok cut |",
         "|---|---:|---:|---:|---:|---:|"]
tot = {"raw": 0, "comp": 0, "rtok": 0}
for r in rows:
    raw, comp, rt = int(r["raw"]), int(r["competitor"]), int(r["rtok"])
    tot["raw"] += raw; tot["comp"] += comp; tot["rtok"] += rt
    cut = lambda v: f"{100 * (1 - v / raw):.1f} %" if raw else "n/a"
    lines.append(f"| `{r['case']}` | {raw} | {comp} | {rt} | {cut(comp)} | {cut(rt)} |")
if tot["raw"]:
    lines.append(f"| **total** | {tot['raw']} | {tot['comp']} | {tot['rtok']} | "
                 f"{100 * (1 - tot['comp'] / tot['raw']):.1f} % | {100 * (1 - tot['rtok'] / tot['raw']):.1f} % |")
lines += ["", "| case | tool | runs | median ms | p95 ms |", "|---|---|---:|---:|---:|"]
for f in sorted((out / "hyperfine").glob("*.json")):
    for res in json.load(open(f))["results"]:
        t = [x * 1000 for x in res["times"]]
        lines.append(f"| `{f.stem}` | {res['command'].split(' ', 1)[0]} | {len(t)} | "
                     f"{statistics.median(t):.2f} | {pct(t, 0.95):.2f} |")
(out / "results.md").write_text("\n".join(lines) + "\n")
print((out / "results.md").read_text())
