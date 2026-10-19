"""rtok vs toon, universal-ctags, aider repo map, code-review-graph.

Run inside the rtok-c14 image: python3 bench/c14/run_c14.py --repo /repo --out /out
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import fixtures
from harness import Tool, measure, save, tokens


def rtok_filter(cmd: str, payload: Path) -> Tool:
    return Tool("rtok", lambda w: ["rtok", "filter", "--cmd", cmd], stdin=lambda w: payload.read_bytes())


def leaves(v):
    if isinstance(v, dict):
        for k, x in v.items():
            yield str(k)
            yield from leaves(x)
    elif isinstance(v, list):
        for x in v:
            yield from leaves(x)
    else:
        yield json.dumps(v) if isinstance(v, bool) or v is None else str(v)


def fidelity(path: Path, results: list) -> str:
    want = set(leaves(json.loads(path.read_text(encoding="utf-8"))))
    parts = []
    for r in results:
        if r.tokens is not None:
            got = sum(1 for w in want if w in r.full)
            parts.append(f"{r.tool} keeps {100 * got / len(want):.0f}% of distinct keys/values")
    return "; ".join(parts)


def toon_cases(repo: Path, out: Path, fx: dict) -> None:
    res = out / "toon.jsonl"
    for name, path in fx.items():
        raw = tokens(path.read_text(encoding="utf-8"))
        tools = [rtok_filter(f"cat {path.name}", path),
                 Tool("toon", lambda w, p=path: ["toon", str(p)])]
        rs = measure(tools, repo)
        save(res, f"json:{name}", raw, rs, note=fidelity(path, rs))
    # a real JSON file from the pinned repo
    real = repo / "web" / "package.json"
    if real.exists():
        rs = measure([rtok_filter("cat web/package.json", real),
                      Tool("toon", lambda w: ["toon", str(real)])], repo)
        save(res, "json:web/package.json", tokens(real.read_text()), rs, note=fidelity(real, rs))


def ctags_cases(repo: Path, out: Path) -> None:
    res = out / "universal-ctags.jsonl"
    target = "src/plugins/json_tree/mod.rs"
    raw = tokens((repo / target).read_text())
    # Case: outline of one large file (what an agent reads instead of the whole file).
    tools = [Tool("rtok", lambda w: ["rtok", "filter", "--cmd", f"cat {target}"],
                  stdin=lambda w: (w / target).read_bytes()),
             Tool("universal-ctags", lambda w: ["ctags", "-x", "--_xformat=%N %K %n", target])]
    save(res, f"outline:{target}", raw, measure(tools, repo),
         note="raw = full file; rtok = its read filter on cat; ctags = symbol table")
    # Case: whole-tree symbol index as text.
    src_tokens = sum(tokens(p.read_text(errors="replace")) for p in (repo / "src").rglob("*.rs"))
    tools = [Tool("universal-ctags", lambda w: ["ctags", "-R", "-x", "--_xformat=%N %K %F:%n", "src"])]
    save(res, "index:src", src_tokens, measure(tools, repo),
         note="rtok has no text symbol-index dump (graph index writes SQLite only)")


def aider_cases(repo: Path, out: Path) -> None:
    res = out / "aider-repo-map.jsonl"
    src_tokens = sum(tokens(p.read_text(errors="replace")) for p in (repo / "src").rglob("*.rs"))
    for budget in (1024, 4096):
        tools = [Tool("aider-repo-map", lambda w, b=budget: [
            "aider", "--show-repo-map", "--map-tokens", str(b), "--model", "gpt-4o",
            "--no-check-update", "--no-analytics", "--no-gitignore", "--yes-always", "--no-show-model-warnings"])]
        save(res, f"repo-map:budget={budget}", src_tokens, measure(tools, repo),
             note="rtok has no repo map; see report")


def rtok_index(w: Path, env: dict) -> None:
    subprocess.run(["rtok", "graph", "index", str(w)], cwd=w, env=env, capture_output=True, check=True)


def crg_build(w: Path, env: dict) -> None:
    subprocess.run(["code-review-graph", "build", "-q", "--repo", str(w)], cwd=w, env=env,
                   capture_output=True, check=True)


def crg_cases(repo: Path, out: Path) -> None:
    res = out / "code-review-graph.jsonl"
    diff = subprocess.run(["git", "diff", "HEAD~5", "HEAD"], cwd=repo, capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout
    tools = [Tool("rtok", lambda w: ["rtok", "filter", "--cmd", "git diff HEAD~5 HEAD"], stdin=lambda w: diff.encode()),
             Tool("crg detect --brief", lambda w: ["code-review-graph", "detect-changes", "--base", "HEAD~5", "--brief"], setup=crg_build),
             Tool("crg detect (json)", lambda w: ["code-review-graph", "detect-changes", "--base", "HEAD~5"], setup=crg_build)]
    save(res, "review:HEAD~5..HEAD", tokens(diff), measure(tools, repo), note="raw = git diff HEAD~5 HEAD")
    for sym in ("estimate", "register_mcp"):
        raw = subprocess.run(["git", "grep", "-n", "-w", sym, "--", "src"], cwd=repo, capture_output=True,
                             text=True).stdout
        tools = [Tool("rtok", lambda w, s=sym: ["rtok", "graph", "impact", s, str(w), "--depth", "1"], setup=rtok_index),
                 Tool("code-review-graph", lambda w, s=sym: ["code-review-graph", "query", "callers_of", s, "--repo", str(w)], setup=crg_build)]
        save(res, f"callers:{sym}", tokens(raw), measure(tools, repo), note="raw = git grep -n -w <sym> -- src")
    tools = [Tool("rtok", lambda w: ["rtok", "graph", "dead", str(w)], setup=rtok_index),
             Tool("code-review-graph", lambda w: ["code-review-graph", "dead-code", "--repo", str(w)], setup=crg_build)]
    save(res, "dead-code", 0, measure(tools, repo), note="no raw baseline; compare absolute tokens")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--only", nargs="*", default=["toon", "ctags", "aider", "crg"])
    a = ap.parse_args()
    import os
    os.environ.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="safe.directory", GIT_CONFIG_VALUE_0="*")
    fx = fixtures.write_all(a.out / "fixtures")
    names = {"toon": "toon", "ctags": "universal-ctags", "aider": "aider-repo-map", "crg": "code-review-graph"}
    for k in a.only:
        (a.out / f"{names[k]}.jsonl").unlink(missing_ok=True)
    if "toon" in a.only: toon_cases(a.repo, a.out, fx)
    if "ctags" in a.only: ctags_cases(a.repo, a.out)
    if "aider" in a.only: aider_cases(a.repo, a.out)
    if "crg" in a.only: crg_cases(a.repo, a.out)


if __name__ == "__main__":
    main()
