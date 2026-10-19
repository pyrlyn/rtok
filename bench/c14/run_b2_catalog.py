"""Tool-catalog cost: raw tools/list vs rtok's MCP wrapper vs caveman-shrink.

Catalogs come from real MCP servers (lean-ctx, engram) plus caveman's own test catalog.
Each catalog is the `tools` array a client receives, serialised compactly.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

from harness import fresh_env, tokens
from mcp_client import Mcp

SERVERS = {
    "lean-ctx (79 tools)": ["/opt/c/lean-ctx", "mcp"],
    "engram (23 tools)": ["/opt/c/engram", "mcp"],
}


def catalog(argv: list, cwd: Path) -> list:
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as h:
        m = Mcp(argv, cwd, fresh_env(Path(h)))
        try:
            return m.tools()
        finally:
            m.close()


def compact(tools: list) -> str:
    return json.dumps({"tools": tools}, separators=(",", ":"), ensure_ascii=False)


def shrink(text: str) -> str:
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as h:
        env = fresh_env(Path(h))
        env["CAVEMAN_CCR_DB"] = str(Path(h) / "ccr.db")
        return subprocess.run(["caveman-shrink"], input=text, capture_output=True, text=True,
                              env=env, check=True).stdout


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    rows = []
    cats = {name: catalog(argv, a.repo) for name, argv in SERVERS.items()}
    cave = json.loads(Path("/opt/caveman/shrink/testdata/catalog.json").read_text())
    cats["caveman testdata"] = cave["tools"] if isinstance(cave, dict) else cave
    for name, tools in cats.items():
        raw = compact(tools)
        wrapped = None
        if name in SERVERS:
            wrapped = compact(catalog(["rtok", "mcp", "--", *SERVERS[name]], a.repo))
        outs = [shrink(raw) for _ in range(3)]
        counts = sorted(tokens(o) for o in outs)
        row = {"case": f"catalog:{name}", "raw": tokens(raw), "tools": len(tools),
               "rtok": tokens(wrapped) if wrapped else None, "caveman-shrink": counts[1],
               "caveman_spread": counts}
        print(row)
        rows.append(row)
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "caveman.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))


if __name__ == "__main__":
    main()
