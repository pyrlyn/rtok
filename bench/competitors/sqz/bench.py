#!/usr/bin/env python3
"""In-container bench: raw vs competitor vs rtok on a fixed command set."""
from __future__ import annotations

import csv
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path
from shutil import which

RUNS = int(os.environ.get("RUNS", "30"))
COMPETITOR = os.environ.get("COMPETITOR", "rtk")
OUT = Path("/out")
FIXTURE = Path("/fixture")
HOME = Path("/tmp/benchhome")
TOK = [sys.executable, "/bench/_lib/tokens.py"]


def run_capture(argv: list[str], cwd: Path) -> bytes:
    env = os.environ.copy()
    env.update(
        {
            "HOME": str(HOME),
            "RTOK_HOME": str(HOME / ".rtok"),
            "NO_COLOR": "1",
            "TERM": "dumb",
            "CARGO_NET_OFFLINE": "true",
        }
    )
    p = subprocess.run(argv, cwd=str(cwd), check=False, capture_output=True, env=env)
    return (p.stdout or b"") + (p.stderr or b"")


def count_tokens(data: bytes) -> int:
    p = subprocess.run(TOK, check=False, input=data, capture_output=True)
    try:
        return int((p.stdout or b"0").decode().strip() or "0")
    except ValueError:
        return 0


def key_for(case: str) -> str:
    return re.sub(r"[^a-zA-Z0-9]+", "_", case).strip("_")


def competitor_argv(case: str) -> list[str]:
    return [COMPETITOR, *shlex.split(case)]


def rtok_argv(case: str) -> list[str]:
    return ["rtok", "run", *shlex.split(case)]


def raw_argv(case: str) -> list[str]:
    return shlex.split(case)


def write_versions() -> None:
    lines = []
    for label, argv in [
        ("rtok", ["rtok", "--version"]),
        (COMPETITOR, [COMPETITOR, "--version"]),
        ("rustc", ["rustc", "-V"]),
        ("cargo", ["cargo", "-V"]),
    ]:
        p = subprocess.run(argv, check=False, capture_output=True, text=True)
        lines.append(f"{label}: {(p.stdout or p.stderr or '').strip()}")
    if FIXTURE.is_dir():
        sha = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=str(FIXTURE),
            check=False,
            capture_output=True,
            text=True,
        )
        lines.append(f"fixture_sha: {(sha.stdout or '').strip()}")
    lines.append(f"tokenizer: tiktoken o200k_base via {sys.executable}")
    (OUT / "versions.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def prepare_fixture() -> None:
    if not FIXTURE.is_dir():
        return
    subprocess.run(["git", "checkout", "--quiet", "--", "."], cwd=str(FIXTURE), check=False)
    subprocess.run(["git", "clean", "-fdq"], cwd=str(FIXTURE), check=False)
    main = FIXTURE / "crates" / "core" / "main.rs"
    if main.is_file():
        text = main.read_text(encoding="utf-8", errors="replace")
        main.write_text("// bench edit\n" + text, encoding="utf-8")
    lib = FIXTURE / "crates" / "searcher" / "src" / "lib.rs"
    if lib.is_file():
        with lib.open("a", encoding="utf-8") as fh:
            fh.write("\nfn bench_added() {}\n")
    (FIXTURE / "bench-untracked.txt").write_text("x\n", encoding="utf-8")


def maybe_hyperfine(case: str, key: str) -> None:
    hf = which("hyperfine")
    if not hf:
        return
    out = OUT / "hyperfine" / f"{key}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    commands = [shlex.join(raw_argv(case)), shlex.join(competitor_argv(case)), shlex.join(rtok_argv(case))]
    subprocess.run(
        [
            hf,
            "--style",
            "none",
            "-N",
            "--warmup",
            "3",
            "--runs",
            str(RUNS),
            "-i",
            "--export-json",
            str(out),
            *commands,
        ],
        cwd=str(FIXTURE if FIXTURE.is_dir() else Path("/")),
        check=False,
    )


def main() -> int:
    HOME.mkdir(parents=True, exist_ok=True)
    (OUT / "samples").mkdir(parents=True, exist_ok=True)
    (OUT / "hyperfine").mkdir(parents=True, exist_ok=True)
    prepare_fixture()
    write_versions()
    cases = [
        "git status",
        "git log -n 50",
        "git log --stat -n 10",
        "git diff",
        "ls -la",
        "find . -name '*.rs' -not -path './target/*'",
        "grep -rn 'fn main' --include=*.rs .",
        "cargo build --locked",
        "cargo test --locked",
    ]
    cwd = FIXTURE if FIXTURE.is_dir() else Path("/")
    rows = []
    for case in cases:
        key = key_for(case)
        raw_b = run_capture(raw_argv(case), cwd)
        (OUT / "samples" / f"{key}.raw").write_bytes(raw_b)
        comp_b = run_capture(competitor_argv(case), cwd)
        (OUT / "samples" / f"{key}.{COMPETITOR}").write_bytes(comp_b)
        rt_b = run_capture(rtok_argv(case), cwd)
        (OUT / "samples" / f"{key}.rtok").write_bytes(rt_b)
        rows.append(
            {
                "case": case,
                "raw": count_tokens(raw_b),
                "competitor": count_tokens(comp_b),
                "rtok": count_tokens(rt_b),
            }
        )
        maybe_hyperfine(case, key)
    with (OUT / "tokens.tsv").open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["case", "raw", "competitor", "rtok"], delimiter="\t")
        w.writeheader()
        w.writerows(rows)
    subprocess.run(
        [sys.executable, "/bench/_lib/summarize.py", str(OUT), COMPETITOR],
        check=False,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
