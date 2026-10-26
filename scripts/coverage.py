#!/usr/bin/env python3
"""Dev-only test coverage (`mise run coverage`); never part of product or release builds.

Kotlin/Native 2.4 has no coverage instrumentation (the old -Xcoverage flags were removed), so:
  * Kotlin: Kover over a JVM target that only `-Prtok.coverage=true` adds, for the modules whose
    sources are all common (root build.gradle.kts `jvmCoverable`). Native-only modules are listed as n/a.
  * C (vendored tree-sitter runtime + grammars): llvm-cov over an instrumented librtok_ts build
    exercised by the :treesitter-native test binary (--c, needs Xcode's llvm-cov / llvm-profdata).
Prints a per-module and total line-coverage table; HTML under build/reports/coverage/.
"""
import os, subprocess, sys, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "build", "reports", "coverage")

def gradle(*tasks):
    subprocess.run(["./gradlew", "-Prtok.coverage=true", "--no-configuration-cache", "-q", *tasks], cwd=ROOT, check=True)

def jacoco_lines(path):
    """(covered, total) lines per JaCoCo XML package root."""
    rep = ET.parse(path).getroot()
    c = next((x for x in rep.findall("counter") if x.get("type") == "LINE"), None)
    return (int(c.get("covered")), int(c.get("covered")) + int(c.get("missed"))) if c is not None else (0, 0)

def modules():
    import re
    s = open(os.path.join(ROOT, "build.gradle.kts")).read()
    return re.findall(r'"([\w-]+)"', re.search(r"val jvmCoverable = setOf\(([^)]*)\)", s).group(1))

def kotlin():
    mods = modules()
    gradle(*[f":{m}:koverXmlReport" for m in mods], "koverXmlReport", "koverHtmlReport")
    rows = []
    for m in mods:
        d = "libs/" + m if os.path.isdir(os.path.join(ROOT, "libs", m)) else m
        x = os.path.join(ROOT, d, "build", "reports", "kover", "report.xml")
        rows.append((m, *jacoco_lines(x)) if os.path.exists(x) else (m, 0, 0))
    return rows, jacoco_lines(os.path.join(OUT, "jvm.xml"))

def c_cov():
    """llvm-cov over the instrumented tree-sitter static library (macOS: xcrun)."""
    env = dict(os.environ, RTOK_TS_COVERAGE="1")
    subprocess.run(["./gradlew", "--no-configuration-cache", "-q", ":treesitter-native:macosArm64Test", "--rerun-tasks"], cwd=ROOT, check=True, env=env)
    mod = os.path.join(ROOT, "libs", "treesitter-native", "build")
    raw = [os.path.join(mod, "coverage", f) for f in os.listdir(os.path.join(mod, "coverage")) if f.endswith(".profraw")]
    data = os.path.join(mod, "coverage", "ts.profdata")
    subprocess.run(["xcrun", "llvm-profdata", "merge", "-sparse", *raw, "-o", data], check=True)
    exe = os.path.join(mod, "bin", "macosArm64", "debugTest", "test.kexe")
    runtime = os.path.join(mod, "vendor", "tree-sitter", "lib", "src")
    subprocess.run(["xcrun", "llvm-cov", "show", exe, "-instr-profile", data, "-format=html", "-output-dir", os.path.join(OUT, "c-html"), runtime], check=True)
    rep = subprocess.run(["xcrun", "llvm-cov", "report", exe, "-instr-profile", data, runtime], check=True, capture_output=True, text=True).stdout
    total = [l for l in rep.splitlines() if l.startswith("TOTAL")][0].split()
    lines, missed = int(total[7]), int(total[8])
    return lines - missed, lines

def pct(c, t):
    return f"{100 * c / t:5.1f}%" if t else "   n/a"

def main():
    rows, (tc, tt) = kotlin()
    print(f"{'module':<18}{'lines':>8}{'covered':>9}{'line %':>8}")
    for m, c, t in rows:
        print(f"{m:<18}{t:>8}{c:>9}{pct(c, t):>8}")
    print(f"{'TOTAL (Kotlin/JVM)':<18}{tt:>8}{tc:>9}{pct(tc, tt):>8}")
    if "--c" in sys.argv:
        cc, ct = c_cov()
        print(f"{'tree-sitter C':<18}{ct:>8}{cc:>9}{pct(cc, ct):>8}")
    print("native-only modules (rtok, rtok-sys, rtok-store, …): n/a — Kotlin/Native has no coverage instrumentation")
    print(f"HTML: {os.path.relpath(OUT, ROOT)}/jvm-html/index.html" + (f", {os.path.relpath(OUT, ROOT)}/c-html/index.html" if "--c" in sys.argv else ""))

if __name__ == "__main__":
    main()
