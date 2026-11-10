#!/usr/bin/env python3
"""Decides between a FULL and an AFFECTED Gradle run, then runs it.

Usage: scripts/smart.py {test|build} [--base REF] [--dry-run]
       scripts/smart.py {test|build} --affected   (force the affected run; mise test-affected/build-affected)

FULL when root/shared build config changed, when many modules changed (more than
SMART_MAX_MODULES, default 3, or more than SMART_MAX_SHARE percent of modules, default 40),
or when the diff base cannot be determined. Otherwise AFFECTED: the changed modules plus every
module that depends on them, transitively. The diff covers base...HEAD plus uncommitted changes.
Base: --base, else $SMART_BASE, else the merge-base with origin/main.
"""
import os, re, subprocess, sys
from pathlib import Path

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHARED = ("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
          "gradle/wrapper/", "build-logic/", "buildSrc/", "mise.toml", "scripts/smart.py")

def git(*a):
    r = subprocess.run(["git", *a], cwd=ROOT, capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None

def modules():
    """Gradle path -> directory, from settings.gradle.kts (include + projectDir remaps)."""
    s = Path(ROOT, "settings.gradle.kts").read_text()
    paths = [p for args in re.findall(r'\binclude\(([^)]*)\)', s)
             for p in re.findall(r'"(:[\w.-]+)"', args)]
    dirs = {p: p[1:] for p in paths}
    m = re.search(r'listOf\(([^)]*)\)\.forEach \{ project\(":\$it"\)\.projectDir = file\("libs/\$it"\)', s)
    for name in re.findall(r'"([\w.-]+)"', m.group(1) if m else ""):
        dirs[":" + name] = "libs/" + name
    return dirs

def deps(dirs):
    """Module -> modules it depends on (project(":x") in its build.gradle.kts)."""
    out = {}
    for p, d in dirs.items():
        f = os.path.join(ROOT, d, "build.gradle.kts")
        out[p] = set(re.findall(r'project\("(:[\w.-]+)"\)', Path(f).read_text())) if os.path.exists(f) else set()
    return out

def decide(base):
    dirs = modules()
    wasm_dir = dirs.pop(":rtok-wasm", None)
    if base is None:
        return "FULL", "diff base could not be determined", dirs, []
    committed = git("diff", "--name-only", f"{base}...HEAD")
    if committed is None:
        return "FULL", f"diff base {base} not found", dirs, []
    changed = set(filter(None, committed.splitlines() + (git("diff", "--name-only", "HEAD") or "").splitlines()
                         + (git("ls-files", "--others", "--exclude-standard") or "").splitlines()))
    # The optional host joins only for its own changes, not every change to a dependency such as rtok-sys.
    if wasm_dir and any(f.startswith(wasm_dir + "/") for f in changed):
        dirs[":rtok-wasm"] = wasm_dir
    by_dir = sorted(dirs.items(), key=lambda kv: -len(kv[1]))
    touched = {next((p for p, d in by_dir if f.startswith(d + "/")), None) for f in changed}
    touched.discard(None)
    # Keep optional projects in a full decision too: the command must enable them when they changed.
    shared = sorted(f for f in changed if f in SHARED or any(f.startswith(s) for s in SHARED if s.endswith("/")))
    if shared:
        return "FULL", "shared build config changed: " + ", ".join(shared), dirs, sorted(touched)
    max_n = int(os.environ.get("SMART_MAX_MODULES", "3")); max_share = float(os.environ.get("SMART_MAX_SHARE", "40"))
    share = 100.0 * len(touched) / max(1, len(dirs))
    if len(touched) > max_n or share > max_share:
        return "FULL", f"{len(touched)} modules changed ({share:.0f}% of {len(dirs)}; limits {max_n} / {max_share:.0f}%)", dirs, sorted(touched)
    d = deps(dirs); affected = set(touched); grew = True
    while grew:
        grew = False
        for p, ds in d.items():
            if p not in affected and ds & affected: affected.add(p); grew = True
    if not changed or not affected:
        return "AFFECTED", "no module sources changed", dirs, []
    return "AFFECTED", f"changed {', '.join(sorted(touched))}; with dependents: {', '.join(sorted(affected))}", dirs, sorted(affected)

def main():
    a = sys.argv[1:]
    kind = a[0] if a and a[0] in ("test", "build") else sys.exit(__doc__)
    base = a[a.index("--base") + 1] if "--base" in a else os.environ.get("SMART_BASE") or git("merge-base", "HEAD", "origin/main")
    decision, reason, dirs, affected = decide(base)
    if "--affected" in a and decision == "FULL" and base is not None:
        decision, reason = "AFFECTED", "forced by --affected (" + reason + ")"
        affected = sorted(dirs)
    print(f"smart {kind}: {decision} — {reason} (base {base or '?'})", flush=True)
    task = {"test": "macosArm64Test", "build": "assemble"}[kind]
    if decision == "FULL":
        cmd = ["./gradlew", task] + (["cliTest", "e2eTest"] if kind == "test" else [])
    elif not affected:
        print("smart: nothing to run"); return 0
    else:
        cmd = ["./gradlew"] + [f"{p}:{task}" for p in affected]
        if kind == "test" and ":rtok" in affected: cmd += [":rtok:cliTest", ":rtok-e2e:e2eTest"]
    if ":rtok-wasm" in affected:
        cmd += ["-Prtok.wasm=true"]
    print("smart: " + " ".join(cmd), flush=True)
    return 0 if "--dry-run" in a else subprocess.call(cmd, cwd=ROOT)

if __name__ == "__main__":
    sys.exit(main())
