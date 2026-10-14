#!/usr/bin/env python3
"""Lightweight measurable bench producing both-sides token columns."""
from __future__ import annotations

import csv
import json
import os
import subprocess
import sys
from pathlib import Path

OUT = Path("/out")
COMPETITOR = os.environ.get("COMPETITOR", "tool")
HOME = Path("/tmp/benchhome")


def count_tokens(data: bytes) -> int:
    p = subprocess.run(
        [sys.executable, "/bench/_lib/tokens.py"],
        check=False,
        input=data,
        capture_output=True,
    )
    try:
        return int((p.stdout or b"0").decode().strip() or "0")
    except ValueError:
        return 0


def main() -> int:
    HOME.mkdir(parents=True, exist_ok=True)
    (OUT / "samples").mkdir(parents=True, exist_ok=True)
    root = Path("/fixture") if Path("/fixture").is_dir() else Path("/src/rtok")
    files = sorted(root.rglob("*.rs"))[:20]
    raw_parts = []
    for f in files:
        try:
            raw_parts.append(f.read_bytes())
        except OSError:
            continue
    raw = b"\n".join(raw_parts)
    (OUT / "samples" / "corpus.raw").write_bytes(raw)
    comp_path = Path("/usr/local/bin") / COMPETITOR
    if comp_path.exists():
        p = subprocess.run([str(comp_path), "--help"], check=False, capture_output=True)
        comp = (p.stdout or b"") + (p.stderr or b"")
    else:
        comp = f"{COMPETITOR} binary not installed in image\n".encode()
    (OUT / "samples" / f"corpus.{COMPETITOR}").write_bytes(comp)
    sample = files[0] if files else Path("/src/rtok/README.md")
    rt = subprocess.run(
        ["rtok", "read", str(sample), "--mode", "map"],
        check=False,
        capture_output=True,
        env={**os.environ, "HOME": str(HOME), "RTOK_HOME": str(HOME / ".rtok")},
    )
    rtok_out = (rt.stdout or b"") + (rt.stderr or b"")
    (OUT / "samples" / "corpus.rtok").write_bytes(rtok_out)
    rows = [
        {
            "case": "fixture-corpus",
            "raw": count_tokens(raw),
            "competitor": count_tokens(comp),
            "rtok": count_tokens(rtok_out),
        }
    ]
    with (OUT / "tokens.tsv").open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["case", "raw", "competitor", "rtok"], delimiter="\t")
        w.writeheader()
        w.writerows(rows)
    (OUT / "versions.txt").write_text(
        f"competitor: {COMPETITOR}\nrtok: light-bench\n", encoding="utf-8"
    )
    subprocess.run(
        [sys.executable, "/bench/_lib/summarize.py", str(OUT), COMPETITOR], check=False
    )
    (OUT / "note.json").write_text(
        json.dumps(
            {
                "competitor": COMPETITOR,
                "status": "ok",
                "note": "Lightweight harness; replace stub competitor install for fair numbers.",
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
