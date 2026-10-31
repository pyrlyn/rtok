#!/usr/bin/env python3
"""Attributes the bytes of an rtok.kexe link to code owners, from an ld64 linker map.

Usage: scripts/size_map.py [MAP] [--top N]
       scripts/size_map.py --link   (link an unstripped release with a map first, then report)

The map comes from `./gradlew :rtok:linkReleaseExecutableMacosArm64 -Pdiffuser.releaseSize=none
-Prtok.linkMap=<file>`: the shipped release strips local symbols, so only an unstripped link can say which
Kotlin package or C object a byte belongs to. Sizes are of the unstripped link; compare shares, not totals
(docs/binary-size.md). Symbols are grouped by section kind (code vs constant data) and by owner.
"""
import collections, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_MAP = os.path.join(ROOT, "rtok", "build", "size.map")
# Kotlin symbols are `_kfun:pkg.Class#member`, `_kclass:pkg.Class`, ...: the package decides the klib.
LIBS = ("kotlinx.coroutines", "kotlinx.serialization", "kotlinx.cinterop", "kotlinx.io", "kotlinx.datetime",
        "kotlin.native", "kotlin.collections", "kotlin.text", "kotlin.sequences", "kotlin.time", "kotlin",
        "androidx.room", "androidx.sqlite", "androidx.collection", "io.ktor", "io.ktor.server", "okio", "chasm",
        "com.akuleshov7", "net.peanuuutz")

def load(path):
    objs, secs, syms, part = {}, [], [], None
    with open(path, errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("# "):
                part = line[2:].split(":")[0] if line.endswith(":") else part
                if line.startswith("# Dead"):
                    break
                continue
            if part == "Object files":
                m = re.match(r"\[\s*(\d+)\] (.*)", line)
                if m: objs[int(m[1])] = m[2]
            elif part == "Sections":
                a = line.split("\t")
                if len(a) == 4: secs.append((int(a[0], 16), int(a[1], 16), a[2], a[3]))
            elif part == "Symbols":
                a = line.split("\t", 2)
                m = re.match(r"\s*\[\s*(\d+)\] (.*)", a[2]) if len(a) == 3 else None
                if m: syms.append((int(a[0], 16), int(a[1], 16), int(m[1]), m[2]))
    return objs, secs, syms

def section_of(secs, addr):
    for a, n, seg, name in secs:
        if a <= addr < a + n: return seg, name
    return "?", "?"

def kotlin_owner(name):
    m = re.match(r"_k\w+:(.*)", name)
    if not m: return "kotlin: runtime/anon"
    pkg = re.split(r"[#(<$]", m[1])[0].rstrip(".")
    parts = pkg.split(".")
    while parts and parts[-1][:1].isupper(): parts.pop()  # drop class names
    pkg = ".".join(parts)
    for lib in sorted(LIBS, key=len, reverse=True):
        if pkg == lib or pkg.startswith(lib + "."):
            return "kotlin: " + lib
    return "kotlin: " + ".".join(parts[:2] or ["(root)"])

def owner(objs, obj, name):
    path = objs.get(obj, "?")
    if "konan_temp" in path or path.endswith(".kexe.o"): return kotlin_owner(name)
    if "treesitter-native" in path: return "C: tree-sitter grammars + runtime"
    if "zstd-native" in path: return "C: zstd"
    if "sqlite3.o" in path or "SqliteBundled" in path or "libandroidXBundledSqlite" in path: return "C: SQLite (androidx.sqlite bundled)"
    if "rtok-sys" in path: return "C: rtok-sys helpers"
    if path.startswith("linker synth"): return "linker"
    return "system: " + os.path.basename(path)

def report(path, top):
    objs, secs, syms = load(path)
    total = sum(n for _, n, _, _ in secs)
    print(f"{path}: {total:,} bytes in sections\n")
    print("Sections (segment,section,bytes):")
    for _, n, seg, name in sorted(secs, key=lambda s: -s[1])[:8]:
        print(f"  {seg:12} {name:18} {n:>12,}")
    kind = collections.Counter(); own = collections.defaultdict(collections.Counter)
    grammar = collections.Counter()
    # Every grammar is its own parser.c.o member of one archive; the exported tree_sitter_<lang> names it.
    lang = {o: n[len("_tree_sitter_"):] for _, _, o, n in syms
            if re.fullmatch(r"_tree_sitter_[a-z0-9_]+", n) and "external_scanner" not in n}
    for addr, size, obj, name in syms:
        seg, sec = section_of(secs, addr)
        k = "code" if sec == "__text" else "const data" if sec == "__const" else "other"
        o = owner(objs, obj, name)
        kind[k] += size; own[o][k] += size; own[o]["all"] += size
        if o.startswith("C: tree"): grammar[lang.get(obj) or os.path.basename(objs[obj])] += size
    print("\nBy section kind:", {k: f"{v:,}" for k, v in kind.items()})
    print(f"\nBy owner (top {top}; code / const data / all):")
    for o, c in sorted(own.items(), key=lambda kv: -kv[1]["all"])[:top]:
        print(f"  {o:48} {c['code']:>12,} {c['const data']:>12,} {c['all']:>12,}")
    if grammar:
        print(f"\nTree-sitter objects (top {top}):")
        for g, n in grammar.most_common(top): print(f"  {g:32} {n:>12,}")
        print(f"  ({len(grammar)} objects, {sum(grammar.values()):,} bytes)")

def main():
    a = sys.argv[1:]
    top = int(a[a.index("--top") + 1]) if "--top" in a else 25
    path = next((x for x in a if not x.startswith("-") and not x.isdigit()), DEFAULT_MAP)
    if "--link" in a:
        subprocess.run(["./gradlew", ":rtok:linkReleaseExecutableMacosArm64", "-Pdiffuser.releaseSize=none",
                        f"-Prtok.linkMap={path}"], cwd=ROOT, check=True)
    if not os.path.exists(path): sys.exit(f"no linker map at {path}; run with --link")
    report(path, top)

if __name__ == "__main__":
    main()
