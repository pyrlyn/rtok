#!/usr/bin/env python3
"""results.json -> summary.md (per command group)."""
from __future__ import annotations

import json
import sys
from pathlib import Path


def pct(raw: int, t: int) -> str:
    return f"{(raw - t) / raw * 100:.1f}%" if raw else "-"


def main(out: Path) -> None:
    rows = json.loads((out / "results.json").read_text(encoding="utf-8"))
    ver = json.loads((out / "versions.json").read_text(encoding="utf-8"))
    cols = list(rows[0]["tools"]) if rows else []
    lines = ["# Bench summary", "", "```", json.dumps(ver, indent=2), "```", ""]
    groups: dict[str, list] = {}
    for r in rows:
        groups.setdefault(r["group"], []).append(r)
    for g, rs in groups.items():
        lines += [f"## `{g}`", "", "| case | raw | " + " | ".join(f"{c} tok (save)" for c in cols) + " | " + " | ".join(f"{c} ms" for c in cols) + " |",
                  "|---|---:|" + "---:|" * (2 * len(cols))]
        tot = {"raw": 0} | {c: 0 for c in cols}
        for r in rs:
            cells, ms = [], []
            tot["raw"] += r["raw"]
            for c in cols:
                t = r["tools"][c]
                if not t.get("supported"):
                    cells.append("n/a"); ms.append("n/a"); continue
                tot[c] += t["tokens"]
                flag = "" if t["deterministic"] else " (nondet!)"
                cells.append(f"{t['tokens']} ({pct(r['raw'], t['tokens'])}){flag}")
                ms.append(f"{t['median_ms']}")
            lines.append(f"| `{r['id']}` | {r['raw']} | " + " | ".join(cells) + " | " + " | ".join(ms) + " |")
        lines.append(f"| **total** | {tot['raw']} | " + " | ".join(f"{tot[c]} ({pct(tot['raw'], tot[c])})" for c in cols) + " |" + " |" * len(cols))
        lines.append("")
    (out / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
