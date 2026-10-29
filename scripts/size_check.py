#!/usr/bin/env python3
"""Links the release rtok binary and fails when it is bigger than its size budget.

Usage: scripts/size_check.py [--target macosArm64] [--budget BYTES] [--no-link] [--binary PATH]

The budget is per target (BUDGETS below); --budget or $RTOK_SIZE_BUDGET overrides it for an experiment.
--no-link checks an already linked binary (CI that built it in an earlier step). Exit 0 within the budget,
1 over it, 2 when the binary or the budget is missing. Where the bytes go: docs/binary-size.md, scripts/size_map.py.
Raise a budget only in the same change that adds the bytes, and say why in the commit.
"""
import os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Budgets of the shipped release link (all releaseSize flags on), in bytes. Set from a measured size plus a margin.
BUDGETS = {"macosArm64": 111_000_000}  # 108,768,408 measured 2026-10-30, plus 2.1%

def option(a, name, default=None):
    return a[a.index(name) + 1] if name in a else default

def main():
    a = sys.argv[1:]
    target = option(a, "--target", "macosArm64")
    budget = option(a, "--budget", os.environ.get("RTOK_SIZE_BUDGET") or BUDGETS.get(target))
    if budget is None:
        print(f"size-check: no budget for {target}; set BUDGETS in scripts/size_check.py or pass --budget", file=sys.stderr)
        return 2
    budget = int(budget)
    binary = option(a, "--binary", os.path.join(ROOT, "rtok", "build", "bin", target, "releaseExecutable", "rtok.kexe"))
    if "--no-link" not in a:
        task = f":rtok:linkReleaseExecutable{target[0].upper()}{target[1:]}"
        print("size-check: ./gradlew " + task, flush=True)
        code = subprocess.call(["./gradlew", task], cwd=ROOT)
        if code != 0: return code
    if not os.path.isfile(binary):
        print(f"size-check: no binary at {binary}", file=sys.stderr)
        return 2
    size = os.path.getsize(binary)
    room = budget - size
    print(f"size-check: {target} {size:,} B, budget {budget:,} B, {'headroom' if room >= 0 else 'OVER by'} {abs(room):,} B "
          f"({100.0 * size / budget:.1f}% of budget)")
    if room < 0:
        print("size-check: FAILED. Find the growth with `python3 scripts/size_map.py --link` (docs/binary-size.md).", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
