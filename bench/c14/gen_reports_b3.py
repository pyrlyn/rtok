"""Render docs/comparison/rtok-vs-<tool>.md for batch 3 from bench/c14/results/b3.jsonl."""
import json
import os
from pathlib import Path

os.chdir(Path(__file__).resolve().parents[2])
ROWS = {r["case"]: r for r in map(json.loads, open("bench/c14/results/b3.jsonl"))}

COMMON = """Measured with `bench/c14/run_b3.py` in image `rtok-c15:3` (`bench/c14/b3.Dockerfile`: `rtok-c14b:2`
plus rustup 1.90.0 with `rust-analyzer`, which serena needs). rtok built from
`12ebeb972e2bea88bec939a9ca9b436db884de13`, input repo checked out at the same commit. Each case runs in a
fresh copy of the repo with a fresh `HOME`. Indexing and seeding happen in an untimed setup step on the same
MCP session. Then come the measured `tools/call`s: 1 warmup + 3 runs, median tokens of the text the model
receives (tiktoken `o200k_base`). Kept = share of the expected anchors literally present in the answer:
the `.rs` files from `git grep -w <sym>` for callers, item names for outlines, seeded fact ids for memory
search. "non-deterministic" means more than 2 tokens of spread across fresh runs; for codebase-memory-mcp
that comes from the temp checkout path inside its qualified names."""


def pct(n, raw):
    return "—" if n is None or not raw else f"{100 * (1 - n / raw):.1f} %"


def table(cases, tools):
    out = ["| Case | Raw | " + " | ".join(tools) + " |", "|---|---:|" + "---:|" * len(tools)]
    for c in cases:
        r = ROWS[c]
        cells = []
        for t in tools:
            x = r["results"].get(t)
            if x is None:
                cells.append("n/a")
            elif x["tokens"] is None:
                cells.append("error: " + (x["error"] or "")[:60].replace("|", "/"))
            else:
                k = f", kept {x['kept']} %" if x.get("kept") is not None else ""
                cells.append(f"{x['tokens']} ({pct(x['tokens'], r['raw'])}{k}, {x['ms']:.0f} ms)")
        out.append(f"| `{c}` | {r['raw'] or '—'} | " + " | ".join(cells) + " |")
    return "\n".join(out)


def catalog(name):
    r = ROWS[f"catalog:{name}"]
    return (f"Tool catalog (`tools/list`, compact JSON): {r['note']}, **{r['raw']} tokens** "
            f"(rtok's own: {ROWS['catalog:rtok']['raw']} tokens for 40 tools). Through `rtok mcp -- <server>`: "
            f"{r['results']['rtok mcp --']['tokens']} tokens (passed through unchanged).")


CODE = ["callers:estimate", "callers:register_mcp", "outline:src/plugins/json_tree/mod.rs", "symbol:estimate",
        "explore:wrap foreign MCP server tools list"]
MEM = [c for c in ROWS if c.startswith("memory:")]

DOCS = {
    "codebase-memory-mcp": ("codebase-memory-mcp 0.11.0 (release `codebase-memory-mcp-linux-arm64-portable.mcpb`, github.com/DeusData/codebase-memory-mcp)",
                            CODE, ["rtok", "codebase-memory-mcp"], """\
Verdict: the fastest index (sub-ms to 36 ms per query) and the most complete caller list for `register_mcp`
(100 % of files vs rtok's 72 %). It loses on ambiguous names: `trace_path estimate` returns only an
"ambiguous, pick one" stub (5 % kept), and `get_code_snippet estimate` needs a second call (both counted).
Its file outline is 4× rtok's size because every row carries a fully qualified name. It is the only tool
that found `src/mcp/wrap.rs` for the natural-language explore query.

What rtok should borrow:
- an `ambiguous` answer that lists the candidates' paths so the next call can pick one (rtok prints `?`);
- ranked full-text search over symbol names and paths for natural-language queries (rtok's `explore`
  only resolves identifiers that literally appear in the query)."""),
    "codegraph": ("codegraph 1.6.2 (release `codegraph-linux-arm64.tar.gz`, github.com/colbymchenry/codegraph)",
                  CODE, ["rtok", "codegraph"], """\
codegraph's MCP surface is one tool, `codegraph_explore`, with a 402-token catalog (the smallest here).
Callers and symbol lookups go through its CLI (`codegraph callers`, `codegraph node`), measured as such;
it has no outline command.

Verdict: `callers` splits the answer per definition and caps it, which keeps the answer small (456 tokens)
but drops most files for `estimate` (35 % kept); for `register_mcp` it keeps 97 %. `node estimate` returns all
5 definitions in full (1149 tokens vs rtok 424). `codegraph_explore` is a large, one-shot dump (6544
tokens) and still misses `src/mcp/wrap.rs` for this query.

What rtok should borrow: the one-tool MCP surface idea. A 400-token catalog that routes everything through
`explore` is 7× cheaper than rtok's 40-tool catalog for sessions that only need code search. A
`[mcp] tools = "code"` profile would cover it."""),
    "serena": ("serena-agent 2.0.0 (PyPI `serena-agent`, LSP backend rust-analyzer 1.90.0)",
               CODE, ["rtok", "serena"], """\
serena's default `claude-code` context exposes 2 tools (`initial_instructions`, `serena_repl`, 1032 tokens).
Measured with `--agent-interface tools` (21 tools, 5612 tokens) so each operation is one call.
rust-analyzer starts cold in every fresh run, so the first semantic call takes 4–22 s.

Verdict: serena is exact rather than textual. `find_referencing_symbols` resolves one definition, so for
`register_mcp` (32 definitions) it reports only the callers of `claude::register_mcp` (3 % of the grep files,
which is correct for that symbol). For `estimate` it returns 6601 tokens of `content_around_reference`.
Its outline is the smallest (203 tokens) but groups names by kind without lines, keeping 59 % of item names.

What rtok should borrow: nothing for token cost. rtok already offers `backend = "lsp"` for exact
references. The kind-grouped outline (`{"Function": [...]}`) is worth a look as a terse mode."""),
    "engram": ("engram 3.4.0 (release `engram_3.4.0_linux_arm64.tar.gz`, github.com/Gentleman-Programming/engram)",
               MEM, ["rtok", "engram", "graymatter"], """\
Same 40 seeded facts (seed 1414) saved through each server's MCP save tool, then 5 searches with limit 10.
rtok's `mem_save` merges notes with the same project + kind + title by design, so 2 of the 40 facts
replaced earlier ones (the 71 % on `tree-sitter`).

Verdict: engram's save reply is about 255 tokens per note (prompt-capture hints, project resolution,
topic-key suggestions), 28× rtok's. Its search returns full observations with metadata: 4–9× rtok's tokens.
Its catalog is 5822 tokens for 23 tools (`--tools=agent` trims it to 19).

What rtok should borrow: nothing for token cost. Its session-summary / timeline tools are a feature, not a saving."""),
    "graymatter": ("graymatter 0.20.1 (release `graymatter_0.20.1_linux_arm64.tar.gz`, github.com/angelnicolasc/graymatter)",
                   MEM, ["rtok", "graymatter", "engram"], """\
Same 40 seeded facts (seed 1414) saved through `memory_add`, then 5 `memory_search` calls.

Verdict: graymatter has the smallest save replies (280 tokens for 40 notes) and plain numbered results
that keep 100 % of the matching facts. It always returns its top 8, padding weak matches (168–266 tokens).
rtok returns only FTS matches, so its answers are smaller (71–172), but its JSON objects repeated keys and
quoting on every hit.

Added to rtok: [#1081](https://github.com/pyrlyn/rtok/pull/1081). `mem_search` now returns one line per note
(`id title: snippet`, title dropped when the snippet repeats it), 22–26 % fewer tokens on these queries
(139 → 108, 92 → 68, 172 → 134, 112 → 88, 71 → 54)."""),
}

for slug, (title, cases, tools, body) in DOCS.items():
    Path(f"docs/comparison/rtok-vs-{slug}.md").write_text(
        f"# rtok vs {title}\n\n{COMMON}\n\n{catalog(slug)}\n\n{table(cases, tools)}\n\n{body}\n")

p = ROWS["proxy:engram behind a proxy"]
rows = "\n".join(f"| {k} | {v['tokens']} | {pct(v['tokens'], p['raw']).replace('-', '+', 1) if v['tokens'] and v['tokens'] > p['raw'] else pct(v['tokens'], p['raw'])} |"
                 for k, v in p["results"].items())
Path("docs/comparison/rtok-vs-mcpproxy-go.md").write_text(f"""# rtok vs mcpproxy-go 0.70.0 (release `mcpproxy-0.70.0-linux-arm64.tar.gz`, github.com/smart-mcp-proxy/mcpproxy-go)

{COMMON}

mcpproxy-go fronts upstream MCP servers. Instead of their tools it exposes 12 of its own
(`retrieve_tools` for BM25 tool search, `call_tool_read|write|destructive`, …). It promises fewer tokens
than loading every upstream catalog. Measured with engram 3.4.0 (23 tools) as the only upstream, stdio
transport (`serve --listen ""`), `quarantined: false` (new upstreams are quarantined by default and expose
nothing). Column = tokens the client holds before its first real call.

| Setup | Tokens | vs engram direct |
|---|---:|---:|
{rows}

{catalog("mcpproxy-go")}

Verdict: with one mid-sized upstream the proxy costs more than the server it hides. Its own catalog
(7065) is already larger than engram's (5822). One `retrieve_tools` adds about 2350 tokens (full mode) or
620 (`--tool-response-mode compact`). The break-even is several large upstreams. rtok's wrapper is lossless
and adds 0 tokens.

What rtok should borrow: lazy tool discovery for wrapped servers. `rtok mcp --` could expose a 2-tool
surface (search + call) when the wrapped catalog exceeds a token budget. Combined with the catalog
compaction from the caveman report, that covers the real use case. Not done yet.
""")
print("ok")
