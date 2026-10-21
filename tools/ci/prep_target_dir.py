#!/usr/bin/env python3
"""Prepare a per-profile Cargo target directory before a CI build.

One target directory per build profile (e.g. target/ci-dev) keeps self-hosted
runners from piling up stale artifacts across runs; sccache supplies the
compiled crates instead.

Rules:
  * the directory is created if missing;
  * if it holds more than MAX_ENTRIES top-level entries, it is removed whole and
    recreated (faster than walking a large tree entry by entry);
  * otherwise its contents are removed, the directory itself is kept.

Usage: prep_target_dir.py <dir> [--keep NAME ...]
Prints the absolute path; appends CARGO_TARGET_DIR to $GITHUB_ENV when --export is given.
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys
from pathlib import Path

MAX_ENTRIES = 3


def _remove(p: Path) -> None:
    if p.is_dir() and not p.is_symlink():
        shutil.rmtree(p, ignore_errors=True)
    else:
        try:
            p.unlink()
        except FileNotFoundError:
            pass


def prep(target: Path, keep: set[str]) -> str:
    target.mkdir(parents=True, exist_ok=True)
    entries = [e for e in target.iterdir() if e.name not in keep]
    if len(entries) > MAX_ENTRIES and not keep:
        shutil.rmtree(target, ignore_errors=True)
        target.mkdir(parents=True, exist_ok=True)
        return f"recreated ({len(entries)} entries > {MAX_ENTRIES})"
    for e in entries:
        _remove(e)
    return f"cleaned ({len(entries)} entries)"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dir")
    ap.add_argument("--keep", action="append", default=[], help="top-level entry to preserve")
    ap.add_argument("--export", action="store_true", help="write CARGO_TARGET_DIR to $GITHUB_ENV")
    args = ap.parse_args()

    target = Path(args.dir).expanduser().resolve()
    action = prep(target, set(args.keep))
    print(f"{target}: {action}")
    if args.export:
        env_file = os.environ.get("GITHUB_ENV")
        if not env_file:
            print("GITHUB_ENV is not set; nothing exported", file=sys.stderr)
            return 1
        with open(env_file, "a", encoding="utf-8") as f:
            f.write(f"CARGO_TARGET_DIR={target}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
