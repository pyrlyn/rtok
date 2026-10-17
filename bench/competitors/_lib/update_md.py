#!/usr/bin/env python3
"""Regenerate the auto numbers block in docs/comparison/rtok-vs-<slug>.md from results.json."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

BEGIN = "<!-- competitor-bench:begin -->"
END = "<!-- competitor-bench:end -->"


def render(summary: dict) -> str:
    lines = [
        BEGIN,
        "",
        "### Harness results (auto-generated)",
        "",
        f"Status: `{summary.get('status', 'unknown')}`. "
        "Numbers below are from the last `bench/competitors/run_all.py` run; "
        "until a real run exists they stay pending.",
        "",
    ]
    totals = summary.get("totals") or {}
    if summary.get("status") == "pending":
        lines += [
            "| side | tokens saved |",
            "|---|---|",
            "| rtok | pending (no run yet) |",
            f"| {summary.get('competitor', 'competitor')} | pending (no run yet) |",
            "",
            END,
        ]
        return "\n".join(lines) + "\n"
    if summary.get("status") == "skipped":
        lines += [
            f"Skipped: {summary.get('skip_reason', 'n/a')}",
            "",
            "| side | tokens saved |",
            "|---|---|",
            "| rtok | no data |",
            f"| {summary.get('competitor', 'competitor')} | no data |",
            "",
            END,
        ]
        return "\n".join(lines) + "\n"
    lines += [
        "| side | tokens saved (vs raw) |",
        "|---|---|",
        f"| rtok | {totals.get('rtok_saved_pct', 'no data')} % |",
        f"| {summary.get('competitor', 'competitor')} | {totals.get('competitor_saved_pct', 'no data')} % |",
        "",
    ]
    md = summary.get("results_md")
    if md:
        lines += [md.rstrip(), ""]
    env = summary.get("environment")
    if env:
        lines += ["Environment:", "", "```text", env.rstrip(), "```", ""]
    lines.append(END)
    return "\n".join(lines) + "\n"


def upsert(md_path: Path, block: str) -> None:
    text = md_path.read_text(encoding="utf-8")
    if BEGIN in text and END in text:
        text = re.sub(
            re.escape(BEGIN) + r"[\s\S]*?" + re.escape(END),
            block.rstrip(),
            text,
            count=1,
        )
    else:
        # append under Own measurements if present
        marker = "## Own measurements"
        if marker in text:
            parts = text.split(marker, 1)
            # keep heading, insert block after first paragraph break following it
            rest = parts[1]
            text = parts[0] + marker + "\n\n" + block + rest.lstrip("\n")
        else:
            text = text.rstrip() + "\n\n" + block
    md_path.write_text(text if text.endswith("\n") else text + "\n", encoding="utf-8")


def main() -> None:
    slug = sys.argv[1]
    results = Path(sys.argv[2])
    root = Path(__file__).resolve().parents[3]
    md_path = root / "docs" / "comparison" / f"rtok-vs-{slug}.md"
    if results.is_file():
        summary = json.loads(results.read_text(encoding="utf-8"))
        sibling = results.with_name("results.md")
        if sibling.is_file():
            summary["results_md"] = sibling.read_text(encoding="utf-8")
        env_parts = []
        for name in ("env.txt", "versions.txt"):
            f = results.with_name(name)
            if f.is_file():
                env_parts.append(f.read_text(encoding="utf-8").rstrip())
        if env_parts:
            summary["environment"] = "\n".join(env_parts) + "\n"
    else:
        summary = {
            "competitor": slug,
            "status": "pending",
            "totals": {},
        }
    upsert(md_path, render(summary))


if __name__ == "__main__":
    main()
