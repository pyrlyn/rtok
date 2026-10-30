#!/usr/bin/env python3
"""Pin and fetch the tree-sitter runtime and grammars used by :treesitter-native.

  python3 scripts/grammars.py lock    # resolve each repo's latest release (or default branch) to a commit
  python3 scripts/grammars.py fetch   # download pinned sources into libs/treesitter-native/build/vendor

The pin file is libs/treesitter-native/grammars.json (checked in). Sources are not committed: they are
fetched as GitHub tarballs at the pinned commit and only src/ and queries/ are kept.
"""
import io, json, os, shutil, subprocess, sys, tarfile, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(ROOT, "libs", "treesitter-native")
PINS = os.path.join(MOD, "grammars.json")
VENDOR = os.path.join(MOD, "build", "vendor")

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

KEEP = ("src/", "queries/", "common/", "lib/src/", "lib/include/")

def fetch():
    pins = json.load(open(PINS))
    os.makedirs(VENDOR, exist_ok=True)
    # grammars() walks the vendor tree, so a repo dropped from the pins must leave it too.
    pinned = {e["repo"].split("/")[1] for e in pins["repos"]}
    for d in os.listdir(VENDOR):
        if os.path.isdir(os.path.join(VENDOR, d)) and d not in pinned:
            shutil.rmtree(os.path.join(VENDOR, d))
            print("removed", d)
    for e in pins["repos"]:
        dest = os.path.join(VENDOR, e["repo"].split("/")[1])
        stamp = os.path.join(dest, ".commit")
        if os.path.exists(stamp) and open(stamp).read() == e["commit"]:
            continue
        url = f"https://codeload.github.com/{e['repo']}/tar.gz/{e['commit']}"
        data = urllib.request.urlopen(url, timeout=120).read()
        with tarfile.open(fileobj=io.BytesIO(data)) as tf:
            for m in tf.getmembers():
                rel = m.name.split("/", 1)[1] if "/" in m.name else ""
                if not m.isfile() or not any(f"/{k}" in f"/{rel}" for k in KEEP):
                    continue
                out = os.path.join(dest, rel)
                os.makedirs(os.path.dirname(out), exist_ok=True)
                with open(out, "wb") as f:
                    f.write(tf.extractfile(m).read())
        open(stamp, "w").write(e["commit"])
        print("fetched", e["repo"], e["commit"][:10])

def c_str(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n\"\n\"") + '"'

def grammars():
    """Every vendored grammar: (symbol name, src dir, queries dir), sorted by name."""
    found = []
    for dirpath, _, files in os.walk(VENDOR):
        if "parser.c" in files and "grammar.json" in files and "/lib/src" not in dirpath:
            name = json.load(open(os.path.join(dirpath, "grammar.json")))["name"]
            base = os.path.dirname(dirpath)
            queries = next((q for q in (os.path.join(base, "queries"), os.path.join(os.path.dirname(base), "queries"),
                                        os.path.join(os.path.dirname(os.path.dirname(base)), "queries")) if os.path.isdir(q)), None)
            found.append((name, dirpath, queries))
    return sorted(found)

MIN_ABI = 13  # oldest grammar ABI the pinned runtime loads

def regenerate_old():
    """Regenerate parser.c with the tree-sitter CLI (mise.toml) for grammars below MIN_ABI (e.g. fluent, ABI 8)."""
    for _, src, _ in grammars():
        head = open(os.path.join(src, "parser.c"), errors="replace").read(4096)
        abi = int(head.split("#define LANGUAGE_VERSION ")[1].split()[0])
        if abi < MIN_ABI:
            subprocess.run(["tree-sitter", "generate", "src/grammar.json"], cwd=os.path.dirname(src), check=True)
            print("regenerated", src, "from ABI", abi)

def table():
    """Generate build/vendor/rtok_ts_table.c: one row per grammar with its language and bundled queries."""
    rows, decls = [], []
    for name, _, queries in grammars():
        q = lambda f: c_str(open(os.path.join(queries, f)).read()) if queries and os.path.exists(os.path.join(queries, f)) else "0"
        decls.append(f"const TSLanguage *tree_sitter_{name}(void);")
        rows.append(f'  {{"{name}", tree_sitter_{name}, {q("tags.scm")}, {q("locals.scm")}, {q("highlights.scm")}}},')
    out = ['#include "rtok_ts.h"', *decls, "const RtokGrammar rtok_ts_grammars[] = {", *rows, "  {0, 0, 0, 0, 0}", "};",
           f"const int rtok_ts_grammar_count = {len(rows)};"]
    open(os.path.join(VENDOR, "rtok_ts_table.c"), "w").write("\n".join(out) + "\n")
    open(os.path.join(VENDOR, "grammars.txt"), "w").write("".join(f"{n}\t{d}\n" for n, d, _ in grammars()))
    print("table:", len(rows), "grammars")

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "fetch": fetch(); regenerate_old(); table(); sys.exit()
    {"lock": lock, "fetch": fetch, "table": table}[sys.argv[1] if len(sys.argv) > 1 else "fetch"]()
