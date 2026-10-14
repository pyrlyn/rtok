#!/opt/tok/bin/python
"""In-container bench: capture once, replay to every tool, count, time."""
from __future__ import annotations

import importlib.util
import json
import os
import random
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import tiktoken

sys.path.insert(0, "/bench")
from cases import CASES, FIXTURE  # noqa: E402

OUT = Path("/out")
RUNS = int(os.environ.get("RUNS", "30"))
WARMUP = 3
SEED = 1071
SHIMS = ["cargo", "git", "ls", "find", "grep", "rg", "cat", "tree", "wc"]
ENC = tiktoken.get_encoding("o200k_base")
DURATION = re.compile(rb"\b\d+(?:\.\d+)?s\b")
BASE_ENV = {"NO_COLOR": "1", "TERM": "dumb", "CARGO_TERM_COLOR": "never", "LC_ALL": "C.UTF-8",
            "CARGO_NET_OFFLINE": "true", "RTK_TELEMETRY_DISABLED": "1", "TZ": "UTC"}


def tokens(b: bytes) -> int:
    return len(ENC.encode(b.decode("utf-8", errors="replace"), disallowed_special=()))


def load_tool(path: Path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fresh_env(extra: dict[str, str], replay: Path | None, home: Path) -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if not k.startswith(("RTOK_", "RTK_", "XDG_"))}
    env.update(BASE_ENV)
    env.update(extra)
    for k in ("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME"):
        env[k] = str(home / k.lower())
    env.update({"HOME": str(home), "RTOK_HOME": str(home / ".rtok"),
                "RTK_DB_PATH": str(home / "rtk.db"), "RTK_TEE_DIR": str(home / "tee"),
                "RTK_DATA_DIR": str(home / "rtkdata"), "RTK_CONFIG_DIR": str(home / "rtkcfg"),
                "RTK_RECALL_DB": str(home / "recall.db")})
    if replay is not None:
        env["BENCH_REPLAY"] = str(replay)
        env["PATH"] = f"/bench/bin{os.pathsep}{env['PATH']}"
    return env


def reset_fixture() -> None:
    subprocess.run(["git", "checkout", "-q", "--", "."], cwd=FIXTURE, check=True)
    subprocess.run(["git", "clean", "-fdq"], cwd=FIXTURE, check=True)


def run_tool(argv: list[str], env_extra: dict[str, str], replay: Path) -> tuple[bytes, int, float]:
    home = Path(tempfile.mkdtemp(prefix="bh-"))
    try:
        env = fresh_env(env_extra, replay, home)
        t0 = time.perf_counter()
        p = subprocess.run(argv, cwd=FIXTURE, env=env, capture_output=True, check=False)
        dt = time.perf_counter() - t0
        return p.stderr + p.stdout, p.returncode, dt
    finally:
        shutil.rmtree(home, ignore_errors=True)


def main() -> int:
    tools = []  # (column, argv_fn)
    rtok = load_tool(Path("/bench/tools/rtok.py"))
    tools.append(("rtok", lambda a: rtok.argv(a, "rtok")))
    if shutil.which("rtok-pr"):
        tools.append(("rtok-pr", lambda a: rtok.argv(a, "rtok-pr")))
        # Only a PR build knows `--no-ref`; probe instead of assuming.
        probe = subprocess.run(["rtok-pr", "run", "--help"], capture_output=True, text=True, check=False)
        if "--no-ref" in probe.stdout:
            tools.append(("rtok-pr-noref", lambda a: rtok.argv(a, "rtok-pr", no_ref=True)))
    comp_name = os.environ["COMPETITOR"]
    comp = load_tool(Path(f"/bench/tools/{comp_name}.py"))
    tools.append((comp_name, comp.argv))

    Path("/bench/bin").mkdir(exist_ok=True)
    for s in SHIMS:
        link = Path("/bench/bin") / s
        if not link.exists():
            link.symlink_to("/bench/replay.py")

    versions = {"fixture_sha": subprocess.run(["git", "rev-parse", "HEAD"], cwd=FIXTURE, capture_output=True, text=True).stdout.strip(),
                "rtok_sha": Path("/rtok.sha").read_text().strip(),
                "tokenizer": f"tiktoken {tiktoken.__version__} o200k_base",
                "runs": RUNS, "warmup": WARMUP, "seed": SEED}
    if Path("/rtok-pr.sha").exists():
        versions["rtok_pr_sha"] = Path("/rtok-pr.sha").read_text().strip()
    for label, argv in [("rtok", ["rtok", "--version"]), (comp_name, comp.VERSION_ARGV),
                        ("cargo", ["cargo", "-V"]), ("git", ["git", "--version"])]:
        p = subprocess.run(argv, capture_output=True, text=True, check=False)
        versions[label] = (p.stdout or p.stderr).strip().splitlines()[0] if (p.stdout or p.stderr) else ""
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "versions.json").write_text(json.dumps(versions, indent=2) + "\n", encoding="utf-8")

    only = set(filter(None, os.environ.get("CASES", "").split(",")))
    rng = random.Random(SEED)
    results = []
    for case in CASES:
        if only and case.id not in only:
            continue
        reset_fixture()
        if case.setup:
            case.setup()
        cdir = OUT / "samples" / case.id
        cdir.mkdir(parents=True, exist_ok=True)
        # Capture once, real command, no shim.
        home = Path(tempfile.mkdtemp(prefix="bh-"))
        p = subprocess.run(case.argv, cwd=FIXTURE, env=fresh_env(case.env, None, home), capture_output=True, check=False)
        shutil.rmtree(home, ignore_errors=True)
        out, err = p.stdout, p.stderr
        if case.argv[0] == "cargo":  # only cargo prints wall-clock times
            out, err = DURATION.sub(b"0.00s", out), DURATION.sub(b"0.00s", err)
        (cdir / "stdout").write_bytes(out)
        (cdir / "stderr").write_bytes(err)
        spec = cdir / "replay.json"
        spec.write_text(json.dumps({"argv": case.argv, "cwd": os.path.realpath(FIXTURE), "exit": p.returncode}), encoding="utf-8")
        raw = err + out
        (cdir / "raw.txt").write_bytes(raw)
        row = {"id": case.id, "group": case.group, "argv": case.argv, "exit": p.returncode,
               "raw": tokens(raw), "tools": {}}
        active = []
        for col, fn in tools:
            targv = fn(case.argv)
            if targv is None:
                row["tools"][col] = {"supported": False}
                continue
            a, ea, _ = run_tool(targv, case.env, spec)
            b, eb, _ = run_tool(targv, case.env, spec)
            (cdir / f"{col}.txt").write_bytes(a)
            row["tools"][col] = {"supported": True, "argv": targv, "tokens": tokens(a), "exit": ea,
                                 "deterministic": a == b and ea == eb, "times": []}
            active.append((col, targv))
        for _ in range(WARMUP):
            for col, targv in active:
                run_tool(targv, case.env, spec)
        for _ in range(RUNS):
            order = active[:]
            rng.shuffle(order)
            for col, targv in order:
                row["tools"][col]["times"].append(run_tool(targv, case.env, spec)[2])
        for col, _ in active:
            ts = row["tools"][col].pop("times")
            row["tools"][col]["median_ms"] = round(statistics.median(ts) * 1000, 2)
            row["tools"][col]["stdev_ms"] = round(statistics.pstdev(ts) * 1000, 2) if ts else 0.0
        results.append(row)
        print(json.dumps({k: row[k] for k in ("id", "raw")} | {c: v.get("tokens") for c, v in row["tools"].items()}), flush=True)
    reset_fixture()
    (OUT / "results.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    subprocess.run([sys.executable, "/bench/summarize.py", str(OUT)], check=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
