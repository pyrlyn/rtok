#!/usr/bin/env python3
"""Host entry point: build the shared base + tool image, run, copy results out.

    python3 bench/harness/run.py rtk [--rtok-pr REF] [--cases a,b] [--runs N]
        [--no-fail-on-negative] [--refresh-fixtures DIR] [--from RESULTS_DIR]

After the run every command group and scenario where rtok-PR saves less than the
competitor is printed as `WARN NEGATIVE: ...`; the exit code is then 3 (after all
results are written) unless --no-fail-on-negative. --refresh-fixtures DIR writes
DIR/<case>/{raw.txt,case.toml} for rtok's tests/fixtures/bench; --from reuses an
existing results dir instead of running Docker.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
ROOT = HERE.parents[1]
BASE_TAG = "rust:1.98.1-trixie"
# Pinned rtok main used both as the fixture and as the "rtok" column.
DEFAULT_REF = "12ebeb972e2bea88bec939a9ca9b436db884de13"
PROFILE = os.environ.get("RTOKBENCH_PROFILE", "rtok-bench")


def sh(argv: list[str], **kw) -> subprocess.CompletedProcess:
    print("+", " ".join(argv), flush=True)
    return subprocess.run(argv, check=False, **kw)


def ensure_docker() -> None:
    if sys.platform != "darwin" or os.environ.get("DOCKER_HOST"):
        return
    sock = Path.home() / ".colima" / PROFILE / "docker.sock"
    if not sock.is_socket():
        sh(["colima", "start", "-p", PROFILE, "--cpu", "4", "--memory", "8", "--disk", "60", "--vm-type", "vz"])
    os.environ["DOCKER_HOST"] = f"unix://{sock}"


def git_sha(ref: str) -> str:
    p = subprocess.run(["git", "rev-parse", ref], cwd=ROOT, capture_output=True, text=True, check=True)
    return p.stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("tool")
    ap.add_argument("--rtok-ref", default=DEFAULT_REF)
    ap.add_argument("--rtok-pr", default="")
    ap.add_argument("--fixture-ref", default=DEFAULT_REF)
    ap.add_argument("--cases", default="")
    ap.add_argument("--runs", default=os.environ.get("RUNS", "30"))
    ap.add_argument("--no-fail-on-negative", action="store_true",
                    help="exit 0 even when rtok-PR saves less than the competitor somewhere")
    ap.add_argument("--refresh-fixtures", default="", metavar="DIR",
                    help="write DIR/<case>/raw.txt + case.toml (rtok tests/fixtures/bench)")
    ap.add_argument("--from", dest="from_dir", default="", metavar="RESULTS_DIR",
                    help="skip the run; post-process an existing results dir")
    a = ap.parse_args()
    if a.from_dir:
        return finish(Path(a.from_dir).resolve(), a, 0)
    tool_dir = HERE / "tools" / a.tool
    if not (tool_dir / "Dockerfile").is_file():
        print(f"no tools/{a.tool}/Dockerfile", file=sys.stderr)
        return 2
    ensure_docker()
    if sh(["docker", "pull", "-q", BASE_TAG]).returncode:
        return 1
    digest = subprocess.run(["docker", "image", "inspect", BASE_TAG, "-f", "{{index .RepoDigests 0}}"],
                            capture_output=True, text=True).stdout.strip() or BASE_TAG
    rtok_sha, fix_sha = git_sha(a.rtok_ref), git_sha(a.fixture_ref)
    pr_sha = git_sha(a.rtok_pr) if a.rtok_pr else ""
    ctx = HERE / ".ctx"
    if ctx.exists():
        shutil.rmtree(ctx)
    shutil.copytree(HERE, ctx, ignore=shutil.ignore_patterns(".ctx", "results", "__pycache__"))
    git = ["git", "-C", str(ROOT)]
    # A bundle carries refs, not bare SHAs: point a throwaway branch at the fixture.
    sh([*git, "branch", "-f", "bench-fixture", fix_sha])
    sh([*git, "bundle", "create", "-q", str(ctx / "fixture.bundle"), "bench-fixture"])
    sh([*git, "branch", "-D", "bench-fixture"], capture_output=True)
    sh([*git, "archive", "-o", str(ctx / "rtok.tar"), rtok_sha])
    if pr_sha:
        sh([*git, "archive", "-o", str(ctx / "rtok-pr.tar"), pr_sha])
    else:
        (ctx / "rtok-pr.tar").write_bytes(b"")
    if sh(["docker", "build", "-f", str(ctx / "Dockerfile.base"), "--build-arg", f"BASE={digest}",
           "--build-arg", f"RTOK_REF={rtok_sha}", "--build-arg", f"RTOK_PR_REF={pr_sha}",
           "--build-arg", f"FIXTURE_REF={fix_sha}", "-t", "rtokbench/base", str(ctx)]).returncode:
        return 1
    image = f"rtokbench/{a.tool}"
    if sh(["docker", "build", "-f", str(ctx / "tools" / a.tool / "Dockerfile"), "-t", image, str(ctx)]).returncode:
        return 1
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    out = HERE / "results" / a.tool / stamp
    out.mkdir(parents=True, exist_ok=True)
    name = f"rtokbench-{a.tool}-{stamp}"
    rc = sh(["docker", "run", "--name", name, "--cpus", "4", "--memory", "8g", "--memory-swap", "8g",
             "--network", "none", "-e", f"RUNS={a.runs}", "-e", f"CASES={a.cases}",
             "-e", f"COMPETITOR={a.tool}", image, "/opt/tok/bin/python", "/bench/bench.py"]).returncode
    # No bind mount (Colima shares only some host paths); copy /out back.
    sh(["docker", "cp", f"{name}:/out/.", str(out)])
    sh(["docker", "rm", "-f", name], capture_output=True)
    print(f"results: {out}")
    return finish(out, a, rc)


def finish(out: Path, a: argparse.Namespace, rc: int) -> int:
    import check  # bench/harness/check.py
    if a.refresh_fixtures:
        check.refresh_fixtures(out, Path(a.refresh_fixtures).resolve())
    losses = check.report_negatives(out, a.tool)
    if rc:
        return rc
    return 3 if losses and not a.no_fail_on_negative else 0


if __name__ == "__main__":
    raise SystemExit(main())
