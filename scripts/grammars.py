#!/usr/bin/env python3
"""Pin and fetch the tree-sitter runtime and grammars used by :treesitter-native.

  python3 scripts/grammars.py fetch   # download pinned sources into libs/treesitter-native/build/vendor
  python3 scripts/grammars.py lock    # re-resolve the commits of the few repos fetched directly ("repos")

Pin file: libs/treesitter-native/grammars.json (checked in). Sources are not committed.
- "pack": most grammars come from ONE release asset of xberg-io/tree-sitter-language-pack
  (parser-sources-<version>.tar.zst), verified by sha256. Only the listed grammars and only
  src/*.c|*.h, src/tree_sitter/*.h, common/ and queries/{tags,locals,highlights}.scm are extracted.
- "repos": the tree-sitter runtime and grammars missing from the pack, fetched as GitHub tarballs at a
  pinned commit; only the "keep" path prefixes (from the repo root) are extracted.
Decompressing .tar.zst uses the `zstd` CLI (see docs/tree-sitter.md).
"""
import hashlib, io, json, os, shutil, subprocess, sys, tarfile, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(ROOT, "libs", "treesitter-native")
PINS = os.path.join(MOD, "grammars.json")
VENDOR = os.path.join(MOD, "build", "vendor")
QUERIES = ("tags.scm", "locals.scm", "highlights.scm")

def gh(path):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True)
    return json.loads(out.stdout) if out.returncode == 0 else None

def lock():
    pins = json.load(open(PINS))
    for e in pins["repos"]:
        ref = e.get("branch")
        if not ref:
            rel = gh(f"repos/{e['repo']}/releases/latest")
            ref = rel["tag_name"] if rel else gh(f"repos/{e['repo']}")["default_branch"]
        sha = gh(f"repos/{e['repo']}/commits/{ref}")["sha"]
        e["ref"], e["commit"] = ref, sha
        print(f"{e['repo']:45} {ref:24} {sha[:10]}")
    json.dump(pins, open(PINS, "w"), indent=2); open(PINS, "a").write("\n")

def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)

def pack_wanted(rel, dirs):
    """parsers/<dir>/... paths to keep from the pack."""
    parts = rel.split("/")
    if len(parts) < 4 or parts[0] != "parsers" or parts[1] not in dirs:
        return False
    sub, base = parts[2], parts[-1]
    if sub == "src":
        return base.endswith((".c", ".h"))
    if sub == "common":
        return True
    return sub == "queries" and len(parts) == 4 and base in QUERIES

def fetch_pack(p):
    url = f"https://github.com/{p['repo']}/releases/download/v{p['version']}/{p['asset']}"
    data = urllib.request.urlopen(url, timeout=300).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != p["sha256"]:
        sys.exit(f"sha256 mismatch for {p['asset']}: {digest} != {p['sha256']}")
    if not shutil.which("zstd"):
        sys.exit("zstd CLI not found (needed to unpack the grammar pack); install zstd")
    tar = subprocess.run(["zstd", "-dc"], input=data, capture_output=True, check=True).stdout
    dirs = {g["dir"] for g in p["grammars"]}
    with tarfile.open(fileobj=io.BytesIO(tar)) as tf:
        for m in tf:
            rel = m.name.lstrip("./")
            if m.isfile() and pack_wanted(rel, dirs):
                write(os.path.join(VENDOR, "pack", rel[len("parsers/"):]), tf.extractfile(m).read())
    print(f"fetched {p['asset']} ({len(data) // 1024} KiB), {len(dirs)} grammars")

def fetch_repo(e):
    dest = os.path.join(VENDOR, e["repo"].split("/")[1])
    url = f"https://codeload.github.com/{e['repo']}/tar.gz/{e['commit']}"
    data = urllib.request.urlopen(url, timeout=120).read()
    with tarfile.open(fileobj=io.BytesIO(data)) as tf:
        for m in tf.getmembers():
            rel = m.name.split("/", 1)[1] if "/" in m.name else ""
            if m.isfile() and any(rel.startswith(k) for k in e["keep"]):
                write(os.path.join(dest, rel), tf.extractfile(m).read())
    print("fetched", e["repo"], e["commit"][:10])

def fetch():
    stamp = os.path.join(VENDOR, ".pins")
    want = hashlib.sha256(open(PINS, "rb").read()).hexdigest()
    if os.path.exists(stamp) and open(stamp).read() == want:
        return
    shutil.rmtree(VENDOR, ignore_errors=True)
    pins = json.load(open(PINS))
    fetch_pack(pins["pack"])
    for e in pins["repos"]:
        fetch_repo(e)
    write(stamp, want.encode())

def c_str(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n\"\n\"") + '"'

def grammars():
    """Every pinned grammar: (symbol name, src dir, queries dir), sorted by name."""
    pins = json.load(open(PINS))
    found = [(g["name"], os.path.join(VENDOR, "pack", g["dir"], "src"), os.path.join(VENDOR, "pack", g["dir"], "queries"))
             for g in pins["pack"]["grammars"]]
    for e in pins["repos"]:
        base = os.path.join(VENDOR, e["repo"].split("/")[1])
        found += [(g["name"], os.path.join(base, g["src"]), os.path.join(base, g["queries"])) for g in e.get("grammars", [])]
    return sorted(found)

MIN_ABI = 13  # oldest grammar ABI the pinned runtime loads

def check_abi():
    for name, src, _ in grammars():
        head = open(os.path.join(src, "parser.c"), errors="replace").read(4096)
        abi = int(head.split("#define LANGUAGE_VERSION ")[1].split()[0])
        if abi < MIN_ABI:
            sys.exit(f"{name}: grammar ABI {abi} < {MIN_ABI}, not loadable by the pinned runtime")

def table():
    """Generate build/vendor/rtok_ts_table.c: one row per grammar with its language and bundled queries."""
    rows, decls = [], []
    for name, _, queries in grammars():
        q = lambda f: c_str(open(os.path.join(queries, f)).read()) if os.path.exists(os.path.join(queries, f)) else "0"
        decls.append(f"const TSLanguage *tree_sitter_{name}(void);")
        rows.append(f'  {{"{name}", tree_sitter_{name}, {q("tags.scm")}, {q("locals.scm")}, {q("highlights.scm")}}},')
    out = ['#include "rtok_ts.h"', *decls, "const RtokGrammar rtok_ts_grammars[] = {", *rows, "  {0, 0, 0, 0, 0}", "};",
           f"const int rtok_ts_grammar_count = {len(rows)};"]
    open(os.path.join(VENDOR, "rtok_ts_table.c"), "w").write("\n".join(out) + "\n")
    open(os.path.join(VENDOR, "grammars.txt"), "w").write("".join(f"{n}\t{d}\n" for n, d, _ in grammars()))
    print("table:", len(rows), "grammars")

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "fetch"
    if cmd == "fetch": fetch(); check_abi(); table(); sys.exit()
    {"lock": lock, "table": table}[cmd]()
