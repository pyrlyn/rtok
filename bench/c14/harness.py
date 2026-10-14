"""Shared harness for rtok vs competitor runs (bench/c14).

Same rules for every tool: one pinned repo commit, a fresh HOME and XDG dirs per run
(no cache, no history), real command output, tiktoken o200k_base, the same warmups
and runs in the same order. Only the tool argv differs. No shell: subprocess with
argv lists and pathlib paths, so it runs the same on macOS, Linux and Windows.
"""
from __future__ import annotations

import json
import os
import shutil
import statistics
import subprocess
import tempfile
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Optional

import tiktoken

ENC = tiktoken.get_encoding("o200k_base")
WARMUPS = 1
RUNS = 3


def tokens(text: str) -> int:
    return len(ENC.encode(text, disallowed_special=()))


@dataclass
class Tool:
    name: str
    argv: Callable[[Path], list]
    stdin: Callable[[Path], Optional[bytes]] = lambda work: None
    setup: Optional[Callable[[Path, dict], None]] = None  # e.g. build an index; not counted


@dataclass
class Result:
    tool: str
    tokens: Optional[int]
    ms: Optional[float]
    error: Optional[str] = None
    sample: str = ""
    full: str = ""


def fresh_env(home: Path) -> dict:
    keep = ("PATH", "LANG", "LC_ALL", "TERM", "VIRTUAL_ENV")
    env = {k: v for k, v in os.environ.items() if k in keep}
    env.update(HOME=str(home), XDG_CACHE_HOME=str(home / ".cache"),
               XDG_DATA_HOME=str(home / ".local" / "share"), XDG_CONFIG_HOME=str(home / ".config"),
               NO_COLOR="1", GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="safe.directory",
               GIT_CONFIG_VALUE_0="*")
    return env


def run_once(tool: Tool, repo: Path) -> tuple:
    with tempfile.TemporaryDirectory(prefix="c14-") as td:
        home = Path(td) / "home"
        work = Path(td) / "repo"
        home.mkdir()
        shutil.copytree(repo, work, symlinks=True)
        env = fresh_env(home)
        if tool.setup:
            tool.setup(work, env)
        t0 = time.perf_counter()
        p = subprocess.run(tool.argv(work), input=tool.stdin(work), cwd=work, env=env,
                           capture_output=True, timeout=900)
        ms = (time.perf_counter() - t0) * 1000
        if p.returncode != 0 or not p.stdout.strip():
            raise RuntimeError(f"exit {p.returncode}: {p.stderr.decode(errors='replace')[-400:]}")
        text = p.stdout.decode("utf-8", errors="replace")
        # Archive ids and temp paths differ per run; normalise only for the equality check.
        return text.replace(str(work), "<repo>"), ms


def measure(tools: list, repo: Path) -> list:
    out = []
    for tool in tools:  # fixed order, equal warmups and runs
        try:
            for _ in range(WARMUPS):
                run_once(tool, repo)
            texts, times = [], []
            for _ in range(RUNS):
                t, ms = run_once(tool, repo)
                texts.append(t)
                times.append(ms)
            counts = sorted(tokens(t) for t in texts)
            spread = None if counts[-1] - counts[0] <= 2 else f"non-deterministic across runs: {counts}"
            out.append(Result(tool.name, int(statistics.median(counts)), round(statistics.median(times), 1),
                              error=spread, sample=texts[0][:800], full=texts[0]))
        except Exception as e:  # reported, never hidden
            out.append(Result(tool.name, None, None, error=str(e)))
    return out


def save(path: Path, case: str, raw_tokens: int, results: list, note: str = "") -> dict:
    row = {"case": case, "raw": raw_tokens, "note": note, "results": [{k: v for k, v in asdict(r).items() if k != "full"} for r in results]}
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")
    for r in results:
        pct = "-" if r.tokens is None or not raw_tokens else f"{100 * (1 - r.tokens / raw_tokens):.1f}%"
        print(f"{case:28} raw={raw_tokens:6} {r.tool:18} tok={r.tokens} save={pct} ms={r.ms} {r.error or ''}")
    return row
