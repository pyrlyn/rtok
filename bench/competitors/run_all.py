#!/usr/bin/env python3
"""Run competitor benches with fixed docker limits; update comparison markdown.

Usage:
  python3 bench/competitors/run_all.py [slug ...]

With no slugs, runs every measurable competitor and prints skip reasons for the rest.
Cross-platform (macOS, Linux, Windows): pathlib + subprocess argv lists only.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE / "_lib") not in sys.path:
    sys.path.insert(0, str(HERE / "_lib"))

from docker_run import main as docker_main  # noqa: E402
from update_md import main as update_md_main  # noqa: E402


def load_registry() -> dict:
    return json.loads((HERE / "registry.json").read_text(encoding="utf-8"))


def latest_results(slug: str) -> Path | None:
    root = HERE / slug / "results"
    if not root.is_dir():
        return None
    runs = sorted([p for p in root.iterdir() if p.is_dir()], reverse=True)
    for run in runs:
        js = run / "results.json"
        if js.is_file():
            return js
    return None


def print_table(slug: str, summary: dict) -> None:
    print(f"\n=== {slug} ===")
    status = summary.get("status", "unknown")
    print(f"status: {status}")
    if status == "skipped":
        print(f"reason: {summary.get('skip_reason', 'n/a')}")
        print("| side | tokens saved |")
        print("|---|---|")
        print("| rtok | no data |")
        print(f"| {slug} | no data |")
        return
    if status == "pending":
        print("| side | tokens saved |")
        print("|---|---|")
        print("| rtok | pending |")
        print(f"| {slug} | pending |")
        return
    totals = summary.get("totals") or {}
    print("| side | tokens saved (vs raw) |")
    print("|---|---|")
    print(f"| rtok | {totals.get('rtok_saved_pct', 'no data')} % |")
    print(f"| {slug} | {totals.get('competitor_saved_pct', 'no data')} % |")
    js = latest_results(slug)
    if js:
        sibling = js.with_name("results.md")
        if sibling.is_file():
            print(sibling.read_text(encoding="utf-8"))


def mark_skipped(slug: str, reason: str) -> Path:
    out = HERE / slug / "results" / "skipped"
    out.mkdir(parents=True, exist_ok=True)
    payload = {
        "competitor": slug,
        "status": "skipped",
        "skip_reason": reason,
        "totals": {},
    }
    path = out / "results.json"
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return path


def mark_pending(slug: str) -> Path:
    out = HERE / slug / "results" / "pending"
    out.mkdir(parents=True, exist_ok=True)
    payload = {"competitor": slug, "status": "pending", "totals": {}}
    path = out / "results.json"
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return path


def main(argv: list[str]) -> int:
    reg = load_registry()
    slugs = argv[1:] or sorted(reg.keys())
    rc = 0
    for slug in slugs:
        meta = reg.get(slug)
        if meta is None:
            print(f"unknown slug: {slug}", file=sys.stderr)
            rc = 1
            continue
        if not meta.get("measurable", False):
            reason = meta.get("skip_reason") or "not measurable"
            print(f"skip {slug}: {reason}")
            js = mark_skipped(slug, reason)
            sys.argv = ["update_md.py", slug, str(js)]
            update_md_main()
            print_table(slug, json.loads(js.read_text(encoding="utf-8")))
            continue
        harness_tool = HERE.parent / "harness" / "tools" / slug / "Dockerfile"
        if harness_tool.is_file():
            # Shared clean harness (bench/harness): one image, replayed real output,
            # fresh state per call. It writes its own results and summary.md.
            code = subprocess.run(
                [sys.executable, str(HERE.parent / "harness" / "run.py"), slug], check=False
            ).returncode
            rc = rc or code
            continue
        try:
            code = docker_main(slug)
        except Exception as exc:  # noqa: BLE001
            print(f"error {slug}: {exc}", file=sys.stderr)
            code = 1
        js = latest_results(slug)
        if js is None:
            js = mark_pending(slug)
            if code == 0:
                code = 1
        sys.argv = ["update_md.py", slug, str(js)]
        update_md_main()
        print_table(slug, json.loads(js.read_text(encoding="utf-8")))
        if code != 0:
            rc = code
    return rc


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
