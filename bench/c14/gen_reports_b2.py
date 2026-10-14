"""Render docs/comparison/rtok-vs-{sqz,lean-ctx,token-optimizer,caveman}.md from bench/c14/results."""
import json
import os
import re
from pathlib import Path

os.chdir(Path(__file__).resolve().parents[2])
rows = [json.loads(l) for l in open("bench/c14/results/shell.jsonl")]
COMMON = """Measured with `bench/c14/run_b2_shell.py` in image `rtok-c14b` (`bench/c14/b2/Dockerfile`):
rtok built from `12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit.
Every tool runs the same real command in a fresh copy of the repo after the same seeded setup
(30 edited files, 20 untracked files, a 2000-line `app.log`, equal mtimes), with a fresh `HOME`,
1 warmup + 3 runs, tokens by tiktoken `o200k_base`. Entry points: `rtok run <cmd>`,
`lean-ctx -c "<cmd>"`, `python3 bash_compress.py <cmd>` (token-optimizer), and
`<cmd> | sqz compress --cmd <bin>` (sqz has no run mode). There is no `cargo` in the image, so cargo
cases are not in this batch. "Kept" = share of the raw output's anchors (commit SHAs as 7-char
prefixes, file paths, WARN/ERROR lines) still literally present. Grouped listings (rtok's
`dir/ (N files): a, b …`) print paths in pieces and score low on this check even when the dir is listed."""


def kept(note, tool):
    m = re.search(re.escape(tool) + r" keeps ([^;]+)", note)
    return m.group(1) if m else "—"


def table(tool):
    out = [f"| Command | Raw | rtok | {tool} | rtok saves | {tool} saves | rtok kept | {tool} kept |",
           "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for r in rows:
        res = {x["tool"]: x for x in r["results"]}

        def cell(t):
            x = res[t]
            return "not run: " + x["error"].split(":")[-1].strip()[:45] if x["tokens"] is None else str(x["tokens"])

        def pct(t):
            x = res[t]
            return "—" if x["tokens"] is None else f"{100 * (1 - x['tokens'] / r['raw']):.1f} %"

        out.append(f"| `{r['case']}` | {r['raw']} | {cell('rtok')} | {cell(tool)} | {pct('rtok')} | {pct(tool)} "
                   f"| {kept(r['note'], 'rtok')} | {kept(r['note'], tool)} |")
    return "\n".join(out)


AFTER = """After [#1079](https://github.com/pyrlyn/rtok/pull/1079) (git log one line per commit), on the same raw bytes:
`git log -n 100` 454 → 2801 tokens, SHAs kept 3 % → 89 %; `git log --stat -n 20` 394 → 2910, kept 9 % → 99 %."""

DOCS = {
    "sqz": ("sqz 2.1.1 (release binary `sqz-v2.1.1-aarch64-unknown-linux-musl`, github.com/ojuschugh1/sqz)", """\
`cargo install sqz` installs a different crate (`sqz` 0.1.0 from sequoia-pgp). The real install route is the
GitHub release binary, used here.

Verdict: sqz is a generic text compressor (n-gram abbreviation `«A1»`, dedup refs). It never drops whole
sections, so it keeps most anchors but saves less. It beats rtok only on diffs (−44 % on `git diff HEAD~5`,
where rtok saves 1.4 %), and loses on logs, listings, `find` and `cat` (0 % on both). Its own docs warn the
abbreviator can corrupt repeated SHAs/paths (`--no-abbrev`).

What rtok needs: a diff body compressor (rtok keeps the diff almost whole). Not done yet."""),
    "lean-ctx": ("lean-ctx 3.11.4 (release binary `lean-ctx-aarch64-unknown-linux-musl`, github.com/yvgude/lean-ctx)", """\
Verdict: lean-ctx is the strongest shell competitor so far:
- `git status`: −40.5 % (`unstaged: ~path …`).
- `ls -la`: −66.6 % (name and size only).
- `grep -rn`: −89.6 %.
- `cat` of a source file: −91.4 %.
- `git log -n 100`: keeps 89 % of commit SHAs by printing one line per commit.

rtok's output is smaller on `ls -laR`, `find` (lean-ctx saves 0 %) and the repetitive log (−94.5 % vs −87.4 %).
Its smaller `git log` outputs came from dropping most commits (see Kept), which #1079 fixes.
On that log lean-ctx keeps more distinct WARN/ERROR lines (45 % vs 18 %).

Added to rtok: [#1079](https://github.com/pyrlyn/rtok/pull/1079), git log one line per commit (the lean-ctx case). AFTER

What rtok still needs:
- `git status` compacted into change groups;
- `ls -la` without permission, owner and date columns;
- a repetitive-log fold that keeps every distinct WARN/ERROR message."""),
    "token-optimizer": ("token-optimizer v5.13.44 (git tag, github.com/alexgreensh/token-optimizer)", """\
The PyPI package `token-optimizer` 0.1.0 is a different project (avikahana/token-optimizer). The real tool
ships as a Claude Code plugin. Its PostToolUse Bash compressor `skills/token-optimizer/scripts/bash_compress.py`
runs the command itself (`shell=False`) and is used here as is.

Verdict: token-optimizer compresses only an allow-list of commands. It leaves `git log` and `find`
untouched (0 %) and refuses `cat` ("command is not eligible for compression"). On diffs it is the most
aggressive tool (−97.9 % on `git diff HEAD~5`) but keeps only 51 % of the changed paths. On the worktree diff
it keeps every path at −79 %. It wins `grep -rn` (−89.7 %). Its `git diff` and `ls -laR` outputs differed by a
few tokens between fresh runs; the median is shown.

What rtok needs: a summary mode for large diffs (file headers, hunk headers, change counts). Not done yet."""),
}
for slug, (title, body) in DOCS.items():
    body = body.replace("AFTER", AFTER)
    Path(f"docs/comparison/rtok-vs-{slug}.md").write_text(f"# rtok vs {title}\n\n{COMMON}\n\n{table(slug)}\n\n{body}\n")

cav = [json.loads(l) for l in open("bench/c14/results/caveman.jsonl")]
t = ["| Catalog | Tools | Raw | rtok (`rtok mcp -- <server>`) | caveman-shrink | caveman saves |",
     "|---|---:|---:|---:|---:|---:|"]
for r in cav:
    name = re.sub(r" \(\d+ tools\)", "", r["case"].split(":", 1)[1])
    t.append(f"| {name} | {r['tools']} | {r['raw']} | {r['rtok'] or '—'} | {r['caveman-shrink']} "
             f"| {100 * (1 - r['caveman-shrink'] / r['raw']):.1f} % |")
Path("docs/comparison/rtok-vs-caveman.md").write_text(f"""# rtok vs caveman v4.0.1 (github.com/JuliusBrussee/caveman)

caveman's headline saving, the terse "caveman" reply style, is a prompt/skill. It changes what a model
writes and needs a live model, so it can't be measured deterministically and is not benchmarked here.
Its deterministic tool is `caveman-shrink` (built from the v4.0.1 tag with Go 1.26.9). It shrinks MCP tool
catalogs: it drops examples and titles and shortens long descriptions, but keeps names, params and enums.

Measured with `bench/c14/run_b2_catalog.py`. The catalogs are the `tools/list` result of real servers in
the image (lean-ctx 3.11.4 in its default profile, engram 3.4.0) plus caveman's own test catalog,
serialised compactly. The rtok number is the catalog as seen through `rtok mcp -- <server>`. caveman-shrink
ran 3 times with a fresh `HOME` and `CAVEMAN_CCR_DB` and gave identical output each time. Tokens: tiktoken `o200k_base`.

{chr(10).join(t)}

Verdict: rtok's MCP wrapper passes foreign catalogs through unchanged (by design: "wrap losslessly"), so
caveman-shrink wins by 10–29 %. Its description cut is lossy, and its README says so.

What rtok needs: an opt-in catalog compaction in `rtok mcp --` (drop `title`/`examples`, cap long
descriptions, keep the schema structure). Not done yet.
""")
print("ok")
