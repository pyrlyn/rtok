"""rtok vs sqz, lean-ctx and token-optimizer on real shell output (batch 2).

Every tool runs the same real command on the same pinned repo, in a fresh HOME, after
the same seeded setup (dirty worktree + a repetitive log file). Each tool uses its own
documented "run a command" entry point; sqz has none, so the command is run once by
the harness and its exact stdout is piped to `sqz compress`.

Run inside rtok-c14b: python3 run_b2_shell.py --repo /repo --out /out
"""
from __future__ import annotations

import argparse
import random
import re
import shlex
import subprocess
from pathlib import Path

from harness import Tool, measure, save, tokens

SEED = 1414
TO = "/opt/token-optimizer/skills/token-optimizer/scripts/bash_compress.py"

CASES = [
    ("git status (dirty)", ["git", "status"]),
    ("git log -n 100", ["git", "log", "-n", "100"]),
    ("git log --stat -n 20", ["git", "log", "--stat", "-n", "20"]),
    ("git diff HEAD~5 HEAD", ["git", "diff", "HEAD~5", "HEAD"]),
    ("git diff (worktree)", ["git", "diff"]),
    ("find src -name *.rs", ["find", "src", "-name", "*.rs"]),
    ("ls -la src/plugins/cmd", ["ls", "-la", "src/plugins/cmd"]),
    ("ls -laR src/plugins", ["ls", "-laR", "src/plugins"]),
    ("grep -rn fn src/plugins/cmd", ["grep", "-rn", "fn ", "src/plugins/cmd"]),
    ("grep -rn TODO src", ["grep", "-rn", "TODO", "src"]),
    ("cat rules.rs", ["cat", "src/plugins/cmd/rules.rs"]),
    ("cat app.log (repetitive)", ["cat", "app.log"]),
]


def seed_worktree(work: Path, env: dict) -> None:
    rng = random.Random(SEED)
    files = sorted(p for p in (work / "src").rglob("*.rs"))
    for p in rng.sample(files, 30):
        with p.open("a", encoding="utf-8") as f:
            f.write(f"\n// bench edit {rng.randint(0, 10**6)}\n")
    for i in range(20):
        (work / f"untracked_{i:02}.txt").write_text(f"note {i}\n", encoding="utf-8")
    levels = ["INFO", "INFO", "INFO", "WARN", "DEBUG", "ERROR"]
    lines = []
    for i in range(2000):
        lvl = rng.choice(levels)
        msg = rng.choice(["request served path=/api/items status=200",
                          "cache miss key=user:42", "retrying connection to db attempt=1",
                          "heartbeat ok", f"slow query took {rng.randint(100, 900)}ms"])
        lines.append(f"2026-10-14T12:{i // 60 % 60:02}:{i % 60:02}Z {lvl} worker-{rng.randint(1, 4)} {msg}")
    (work / "app.log").write_text("\n".join(lines) + "\n", encoding="utf-8")
    # equal mtimes so ls -la prints the same bytes on every run
    subprocess.run(["find", str(work), "-path", str(work / ".git"), "-prune", "-o", "-exec",
                    "touch", "-h", "-d", "2026-10-14 12:00:00", "{}", "+"], check=True, capture_output=True)


ANCHOR = re.compile(r"\b[0-9a-f]{40}\b|(?:src|crates|tests|docs)/[\w./-]+\.\w+|untracked_\d\d\.txt|\b(?:ERROR|WARN)\b.*$", re.M)


def anchors_kept(raw: str, out: str) -> str:
    """Share of the raw output's anchors (commit SHAs as 7-char prefixes, file paths,
    WARN/ERROR lines) that are still visible in a tool's output."""
    want = set()
    for m in ANCHOR.findall(raw):
        want.add(m[:7] if re.fullmatch(r"[0-9a-f]{40}", m) else m)
    if not want:
        return "n/a"
    got = sum(1 for w in want if w in out)
    return f"{100 * got / len(want):.0f}% of {len(want)}"


def raw_output(argv: list, work: Path, env: dict) -> bytes:
    return subprocess.run(argv, cwd=work, env=env, capture_output=True).stdout


def tools_for(argv: list) -> list:
    stem = argv[0]
    return [
        Tool("rtok", lambda w, a=argv: ["rtok", "run", *a], setup=seed_worktree),
        Tool("sqz", lambda w, s=stem: ["/opt/c/sqz", "compress", "--cmd", s], setup=seed_worktree,
             stdin=None),
        Tool("lean-ctx", lambda w, a=argv: ["/opt/c/lean-ctx", "-c", shlex.join(a)], setup=seed_worktree),
        Tool("token-optimizer", lambda w, a=argv: ["python3", TO, *a], setup=seed_worktree),
        Tool("raw", lambda w, a=argv: list(a), setup=seed_worktree),
    ]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    import os
    os.environ.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="safe.directory", GIT_CONFIG_VALUE_0="*")
    res = a.out / "shell.jsonl"
    res.unlink(missing_ok=True)
    for name, argv in CASES:
        tools = tools_for(argv)
        # sqz reads the command's stdout, produced in the same fresh, seeded tree
        tools[1].stdin = lambda w, av=argv: raw_output(av, w, None)
        results = measure(tools, a.repo)
        raw = next((r.tokens for r in results if r.tool == "raw"), None) or 0
        raw_text = next((r.full for r in results if r.tool == "raw"), "")
        kept = "; ".join(f"{r.tool} keeps {anchors_kept(raw_text, r.full)}" for r in results
                         if r.tool != "raw" and r.tokens is not None)
        save(res, name, raw, [r for r in results if r.tool != "raw"], note="anchors: " + kept)


if __name__ == "__main__":
    main()
