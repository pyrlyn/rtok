#!/usr/bin/env python3
"""Host-side orchestrator: build image, run container with fixed CPU/RAM limits."""
from __future__ import annotations

import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from env import write_env

CPUS = "4"
MEMORY = "8g"
PROFILE = os.environ.get("RTOKBENCH_PROFILE", "rtok-bench")
BASE_TAG = os.environ.get("BASE_TAG", "rust:1.98.1-trixie")


def run(argv: list[str], **kw) -> subprocess.CompletedProcess[str]:
    return subprocess.run(argv, check=False, text=True, capture_output=True, **kw)


def ensure_colima() -> None:
    if sys.platform != "darwin" or os.environ.get("DOCKER_HOST"):
        return
    st = run(["colima", "status", "-p", PROFILE], cwd=str(Path.home()))
    if st.returncode == 0:
        return
    subprocess.run(
        [
            "colima",
            "start",
            "-p",
            PROFILE,
            "--cpu",
            "4",
            "--memory",
            "8",
            "--disk",
            "60",
            "--vm-type",
            "vz",
        ],
        check=False,
        cwd=str(Path.home()),
    )
    sock = Path.home() / ".colima" / PROFILE / "docker.sock"
    if sock.is_socket():
        os.environ["DOCKER_HOST"] = f"unix://{sock}"


def main(slug: str) -> int:
    here = Path(__file__).resolve().parent
    root = here.parents[2]
    slug_dir = here.parent / slug
    if not (slug_dir / "Dockerfile").is_file():
        print(f"error: missing Dockerfile for {slug}", file=sys.stderr)
        return 2
    ensure_colima()
    out = slug_dir / "results" / datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    pull = run(["docker", "pull", "-q", BASE_TAG])
    if pull.returncode != 0:
        print(pull.stderr, file=sys.stderr)
        return pull.returncode
    insp = run(
        ["docker", "image", "inspect", BASE_TAG, "-f", "{{index .RepoDigests 0}}"]
    )
    base = (insp.stdout or BASE_TAG).strip() or BASE_TAG
    image = f"rtokbench/{slug}"
    build = subprocess.run(
        [
            "docker",
            "build",
            "-f",
            str(slug_dir / "Dockerfile"),
            "--build-arg",
            f"BASE={base}",
            "-t",
            image,
            str(root),
        ],
        check=False,
    )
    if build.returncode != 0:
        return build.returncode
    write_env(out, image)
    # No bind mount: Colima/Docker Desktop only share some host paths, so a
    # mount outside them silently lands inside the VM. Copy /out back instead.
    name = f"rtokbench-{slug}-{out.name}"
    run(["docker", "rm", "-f", name])
    docker_run = subprocess.run(
        [
            "docker",
            "run",
            "--name",
            name,
            "--cpus",
            CPUS,
            "--memory",
            MEMORY,
            "--memory-swap",
            MEMORY,
            "--network",
            "none",
            "-e",
            f"RUNS={os.environ.get('RUNS', '30')}",
            "-e",
            f"COMPETITOR={slug}",
            image,
        ],
        check=False,
    )
    cp = run(["docker", "cp", f"{name}:/out/.", str(out)])
    if cp.returncode != 0:
        print(cp.stderr, file=sys.stderr)
    run(["docker", "rm", "-f", name])
    print(f"results: {out}")
    return docker_run.returncode


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: docker_run.py <slug>", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1]))
