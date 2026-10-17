#!/usr/bin/env python3
"""Update pinned rtok competitor versions in bench Dockerfiles.

Reads install pins from:
  bench/c14/Dockerfile
  bench/c14/b2/Dockerfile
  bench/c14/b2/Dockerfile.cave
  bench/harness/tools/rtk/Dockerfile

Detects each tool's install method (GitHub release binary, git tag clone,
PyPI/uv tool, npm, apt). Queries latest upstream version.

  --check   dry-run report only (default when neither flag is set)
  --apply   rewrite pins in those Dockerfiles (never installs on the host)

Cross-platform: pathlib + urllib + subprocess (gh api when available). No shell.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

UA = "rtok-bench-update-competitors/1.0"
GH_API = "https://api.github.com"


@dataclass(frozen=True)
class Competitor:
    name: str
    method: str  # github_release | github_tag | pypi | npm | apt
    pin_files: tuple[str, ...]
    # Regexes tried in order; first with a capture group wins.
    pin_res: tuple[str, ...]
    repo: str | None = None
    pypi: str | None = None
    npm: str | None = None
    notes: str = ""


COMPETITORS: tuple[Competitor, ...] = (
    Competitor(
        name="rtk",
        method="github_release",
        pin_files=("bench/harness/tools/rtk/Dockerfile",),
        pin_res=(r"RTK_VERSION=(v?[0-9][^\s\"']+)",),
        repo="rtk-ai/rtk",
        notes="GitHub release binary -> rtokbench/rtk image",
    ),
    Competitor(
        name="sqz",
        method="github_release",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(r"ojuschugh1/sqz/releases/download/(v[0-9][^/]+)/",),
        repo="ojuschugh1/sqz",
        notes="NOT crates.io sqz; GitHub musl release binary",
    ),
    Competitor(
        name="lean-ctx",
        method="github_release",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(r"yvgude/lean-ctx/releases/download/(v[0-9][^/]+)/",),
        repo="yvgude/lean-ctx",
        notes="GitHub musl release binary",
    ),
    Competitor(
        name="token-optimizer",
        method="github_tag",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(
            r"git clone[^\n]*-b\s+(v[0-9][^\s]+)\s+\$GH/alexgreensh/token-optimizer",
            r"git clone[^\n]*-b\s+(v[0-9][^\s]+)\s+https://github.com/alexgreensh/token-optimizer",
        ),
        repo="alexgreensh/token-optimizer",
        notes="git tag clone; bash_compress.py — NOT PyPI token-optimizer",
    ),
    Competitor(
        name="caveman",
        method="github_tag",
        pin_files=("bench/c14/b2/Dockerfile", "bench/c14/b2/Dockerfile.cave"),
        pin_res=(
            r"git clone[^\n]*-b\s+(v[0-9][^\s]+)\s+\$GH/JuliusBrussee/caveman",
            r"git clone[^\n]*-b\s+(v[0-9][^\s]+)\s+https://github.com/JuliusBrussee/caveman",
        ),
        repo="JuliusBrussee/caveman",
        notes="git tag + build caveman-shrink (Dockerfile.cave)",
    ),
    Competitor(
        name="engram",
        method="github_release",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(r"Gentleman-Programming/engram/releases/download/(v[0-9][^/]+)/",),
        repo="Gentleman-Programming/engram",
        notes="GitHub linux_arm64 release binary",
    ),
    Competitor(
        name="graymatter",
        method="github_release",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(r"angelnicolasc/graymatter/releases/download/(v[0-9][^/]+)/",),
        repo="angelnicolasc/graymatter",
        notes="GitHub linux_arm64 release binary",
    ),
    Competitor(
        name="mcpproxy-go",
        method="github_release",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(r"smart-mcp-proxy/mcpproxy-go/releases/download/(v[0-9][^/]+)/",),
        repo="smart-mcp-proxy/mcpproxy-go",
        notes="GitHub release; binary name mcpproxy",
    ),
    Competitor(
        name="codegraph",
        method="github_release",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(r"colbymchenry/codegraph/releases/download/(v[0-9][^/]+)/",),
        repo="colbymchenry/codegraph",
        notes="GitHub linux-arm64 tarball",
    ),
    Competitor(
        name="codebase-memory-mcp",
        method="github_release",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(r"DeusData/codebase-memory-mcp/releases/download/(v[0-9][^/]+)/",),
        repo="DeusData/codebase-memory-mcp",
        notes="GitHub .mcpb portable",
    ),
    Competitor(
        name="serena",
        method="pypi",
        pin_files=("bench/c14/b2/Dockerfile",),
        pin_res=(r"serena-agent==([0-9][^\s\"']+)",),
        pypi="serena-agent",
        notes="uv tool install serena-agent (oraios/serena)",
    ),
    Competitor(
        name="toon",
        method="npm",
        pin_files=("bench/c14/Dockerfile",),
        pin_res=(r"@toon-format/cli@([0-9][^\s\"']+)",),
        npm="@toon-format/cli",
        notes="npm global @toon-format/cli",
    ),
    Competitor(
        name="code-review-graph",
        method="pypi",
        pin_files=("bench/c14/Dockerfile",),
        pin_res=(r"code-review-graph==([0-9][^\s\"']+)",),
        pypi="code-review-graph",
        notes="pip in image venv",
    ),
    Competitor(
        name="aider-repo-map",
        method="pypi",
        pin_files=("bench/c14/Dockerfile",),
        pin_res=(r"aider-chat==([0-9][^\s\"']+)",),
        pypi="aider-chat",
        notes="uv tool install aider-chat",
    ),
    Competitor(
        name="universal-ctags",
        method="apt",
        pin_files=("bench/c14/Dockerfile",),
        pin_res=(r"\buniversal-ctags\b",),
        repo="universal-ctags/ctags",
        notes="apt in image; latest = upstream GitHub tag (info only)",
    ),
)


def http_json(url: str, headers: dict[str, str] | None = None) -> object:
    h = {"User-Agent": UA, "Accept": "application/json"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and "api.github.com" in url:
        h["Authorization"] = f"Bearer {token}"
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=45) as resp:
        return json.load(resp)


def _gh_api_json(path: str) -> object:
    """Prefer authenticated `gh api` (subprocess); fall back to urllib."""
    try:
        proc = subprocess.run(
            ["gh", "api", path],
            check=False,
            capture_output=True,
            text=True,
            timeout=45,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        proc = None
    if proc is not None and proc.returncode == 0 and proc.stdout.strip():
        return json.loads(proc.stdout)
    return http_json(f"{GH_API}{path}", {"Accept": "application/vnd.github+json"})


def gh_latest_release_tag(repo: str) -> str:
    data = _gh_api_json(f"/repos/{repo}/releases/latest")
    assert isinstance(data, dict)
    tag = data.get("tag_name")
    if not tag:
        raise RuntimeError(f"no tag_name for {repo}")
    return str(tag)


def gh_latest_tag(repo: str) -> str:
    data = _gh_api_json(f"/repos/{repo}/tags?per_page=30")
    assert isinstance(data, list) and data, f"no tags for {repo}"
    for item in data:
        name = str(item.get("name", ""))
        if re.match(r"^v?\d", name):
            return name
    return str(data[0]["name"])


def pypi_latest(package: str) -> str:
    data = http_json(f"https://pypi.org/pypi/{package}/json")
    assert isinstance(data, dict)
    return str(data["info"]["version"])


def npm_latest(package: str) -> str:
    data = http_json(f"https://registry.npmjs.org/{package}")
    assert isinstance(data, dict)
    return str(data["dist-tags"]["latest"])


def find_roots(explicit: list[Path]) -> list[Path]:
    if explicit:
        return [p.resolve() for p in explicit]
    here = Path(__file__).resolve().parent
    out: list[Path] = []
    for c in (here.parent.parent, here.parent, Path.cwd()):
        if (c / "bench").is_dir() and c not in out:
            out.append(c)
    return out or [Path.cwd()]


def read_pin(roots: list[Path], comp: Competitor) -> tuple[str | None, Path | None, str]:
    for root in roots:
        for rel in comp.pin_files:
            path = root / rel
            if not path.is_file():
                continue
            text = path.read_text(encoding="utf-8")
            if comp.method == "apt":
                if "universal-ctags" in text:
                    return ("apt", path, f"pin in {path}")
                continue
            for pat in comp.pin_res:
                m = re.search(pat, text)
                if m and m.lastindex:
                    return (m.group(1), path, f"pin in {path}")
    return (None, None, "pin file not found under --root")


def fetch_latest(comp: Competitor) -> str:
    if comp.method == "github_release":
        assert comp.repo
        return gh_latest_release_tag(comp.repo)
    if comp.method == "github_tag":
        assert comp.repo
        try:
            return gh_latest_release_tag(comp.repo)
        except Exception:
            return gh_latest_tag(comp.repo)
    if comp.method == "pypi":
        assert comp.pypi
        return pypi_latest(comp.pypi)
    if comp.method == "npm":
        assert comp.npm
        return npm_latest(comp.npm)
    if comp.method == "apt":
        assert comp.repo
        try:
            return gh_latest_release_tag(comp.repo)
        except Exception:
            return gh_latest_tag(comp.repo)
    raise RuntimeError(comp.method)


def bare(v: str) -> str:
    return v[1:] if re.match(r"^v\d", v) else v


def same_version(a: str, b: str) -> bool:
    if a == "apt":
        return False
    return a == b or bare(a) == bare(b)


def style_new(old: str, latest: str) -> str:
    if old.startswith("v") and not latest.startswith("v"):
        return "v" + latest
    if not old.startswith("v") and latest.startswith("v") and re.match(r"^v\d", latest):
        # keep unprefixed pins (pypi/npm) without v
        if re.match(r"^\d", old):
            return latest[1:]
    return latest


def replace_pin_in_text(comp: Competitor, text: str, old: str, latest: str) -> str:
    if comp.method == "apt":
        return text
    new = style_new(old, latest)
    if comp.method == "github_release" and comp.repo:
        text = text.replace(
            f"{comp.repo}/releases/download/{old}/",
            f"{comp.repo}/releases/download/{new}/",
        )
        b_old, b_new = bare(old), bare(new)
        if old != new:
            text = text.replace(old, new)
        if b_old != b_new:
            for pref in (
                f"sqz-v{b_old}",
                f"sqz-{b_old}",
                f"engram_{b_old}_",
                f"engram-{b_old}-",
                f"graymatter_{b_old}_",
                f"graymatter-{b_old}-",
                f"mcpproxy-{b_old}-",
            ):
                if pref in text:
                    text = text.replace(pref, pref.replace(b_old, b_new, 1))
            text = re.sub(rf"(?<=[_-]){re.escape(b_old)}(?=[_-])", b_new, text)
        return text
    if comp.name in ("token-optimizer", "caveman"):
        return re.sub(rf"(-b\s+){re.escape(old)}\b", rf"\g<1>{new}", text)
    if comp.pypi:
        return text.replace(f"{comp.pypi}=={old}", f"{comp.pypi}=={new}")
    if comp.npm:
        return text.replace(f"{comp.npm}@{old}", f"{comp.npm}@{new}")
    if comp.name == "rtk":
        return re.sub(rf"(RTK_VERSION=){re.escape(old)}", rf"\g<1>{new}", text)
    return text.replace(old, new)


def apply_update(roots: list[Path], comp: Competitor, old: str, latest: str) -> list[Path]:
    changed: list[Path] = []
    for root in roots:
        for rel in comp.pin_files:
            path = root / rel
            if not path.is_file():
                continue
            text = path.read_text(encoding="utf-8")
            new_text = replace_pin_in_text(comp, text, old, latest)
            if new_text != text:
                path.write_text(new_text, encoding="utf-8")
                changed.append(path)
    return changed


@dataclass
class Row:
    name: str
    method: str
    current: str
    latest: str
    status: str
    detail: str


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="dry-run (default if --apply omitted)")
    ap.add_argument("--apply", action="store_true", help="rewrite Dockerfile pins only; no host install")
    ap.add_argument("--root", action="append", type=Path, default=None, help="repo root with bench/ (repeatable)")
    ap.add_argument("--only", nargs="*", default=None, help="limit to these names")
    args = ap.parse_args(argv)
    if args.apply and args.check:
        print("error: use either --check or --apply", file=sys.stderr)
        return 2
    check = not args.apply

    roots = find_roots(list(args.root or []))
    only = set(args.only) if args.only else None
    rows: list[Row] = []

    for comp in COMPETITORS:
        if only and comp.name not in only:
            continue
        current, pin_path, detail = read_pin(roots, comp)
        try:
            latest = fetch_latest(comp)
            err = None
        except Exception as exc:  # noqa: BLE001 — report per row
            latest, err = "?", str(exc)

        if current is None:
            status = "MISSING_PIN"
        elif err:
            status = f"LATEST_ERROR: {err}"
        elif comp.method == "apt":
            status = f"apt (upstream {latest})"
        elif same_version(current, latest):
            status = "up to date"
        else:
            status = f"UPDATE {current} -> {latest}"

        if (not check) and status.startswith("UPDATE") and current:
            changed = apply_update(roots, comp, current, latest)
            if changed:
                status = f"UPDATED {current} -> {latest}"
                detail += "; wrote " + ", ".join(str(p) for p in changed)
            else:
                status = f"UPDATE_NO_WRITE {current} -> {latest}"

        if comp.notes:
            detail = f"{detail}; {comp.notes}"
        rows.append(Row(comp.name, comp.method, current or "—", latest, status, detail))

    mode = "CHECK (no changes)" if check else "APPLY (Dockerfile pins only)"
    print(f"rtok competitor updater — {mode}")
    print(f"roots: {', '.join(str(r) for r in roots)}")
    print()
    nw = max((len(r.name) for r in rows), default=4)
    cw = max((len(r.current) for r in rows), default=7)
    lw = max((len(r.latest) for r in rows), default=6)
    hdr = f"{'name':<{nw}}  {'method':<15}  {'current':<{cw}}  {'latest':<{lw}}  status"
    print(hdr)
    print("-" * len(hdr))
    for r in rows:
        print(f"{r.name:<{nw}}  {r.method:<15}  {r.current:<{cw}}  {r.latest:<{lw}}  {r.status}")
    print()
    for r in rows:
        print(f"  {r.name}: {r.detail}")
    n_ok = sum(1 for r in rows if r.status == "up to date")
    n_behind = sum(1 for r in rows if "UPDATE" in r.status or r.status.startswith("UPDATED"))
    n_miss = sum(1 for r in rows if r.status == "MISSING_PIN")
    print()
    print(f"summary: {n_ok} up to date, {n_behind} behind, {n_miss} missing pin, {len(rows)} total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
