#!/usr/bin/env python3
"""Write a cross-platform env.txt for a bench run (stdlib only)."""
from __future__ import annotations

import os
import platform
import subprocess
from pathlib import Path


def _run(argv: list[str]) -> str:
    try:
        p = subprocess.run(argv, check=False, capture_output=True, text=True)
    except FileNotFoundError:
        return ""
    return (p.stdout or "").strip()


def collect(image: str) -> list[str]:
    lines = [
        f"platform: {platform.system()} {platform.release()} {platform.machine()}",
        f"python: {platform.python_version()}",
        f"docker_run: --cpus 4 --memory 8g --memory-swap 8g",
        f"image: {image}",
    ]
    if platform.system() == "Darwin":
        for key, argv in [
            ("host_model", ["sysctl", "-n", "hw.model"]),
            ("host_cpu", ["sysctl", "-n", "machdep.cpu.brand_string"]),
            ("host_ncpu", ["sysctl", "-n", "hw.ncpu"]),
            ("host_ram_bytes", ["sysctl", "-n", "hw.memsize"]),
        ]:
            val = _run(argv)
            if val:
                lines.append(f"{key}: {val}")
        ver = _run(["sw_vers", "-productVersion"])
        if ver:
            lines.append(f"macos: {ver}")
        colima = _run(["colima", "version"])
        if colima:
            lines.append(f"colima: {colima.splitlines()[0]}")
    elif platform.system() == "Linux":
        cpu = Path("/proc/cpuinfo")
        if cpu.is_file():
            for line in cpu.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("model name"):
                    lines.append(f"host_cpu: {line.split(':', 1)[1].strip()}")
                    break
        mem = Path("/proc/meminfo")
        if mem.is_file():
            for line in mem.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("MemTotal"):
                    lines.append(f"host_ram: {line.split(':', 1)[1].strip()}")
                    break
        lines.append(f"host_ncpu: {os.cpu_count()}")
    img = _run(["docker", "image", "inspect", image, "-f", "{{.Id}}"])
    if img:
        lines.append(f"image_id: {img}")
    base = _run(
        ["docker", "image", "inspect", image, "-f", '{{index .Config.Labels "bench.base"}}']
    )
    if base:
        lines.append(f"base_image: {base}")
    return lines


def write_env(out_dir: Path, image: str) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "env.txt"
    path.write_text("\n".join(collect(image)) + "\n", encoding="utf-8")
    return path


if __name__ == "__main__":
    import sys

    write_env(Path(sys.argv[1]), sys.argv[2])
