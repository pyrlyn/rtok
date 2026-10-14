"""Batch 3: rtok vs MCP servers (codebase-memory-mcp, codegraph, engram, graymatter, mcpproxy-go, serena).

Run inside image rtok-c14b:2: python3 bench/c14/run_b3.py --repo /repo --out /out

Every case: a fresh copy of the pinned repo and a fresh HOME, indexing / seeding done in an
untimed setup step on the same MCP session, then the measured tools/call(s). 1 warmup + 3 runs,
tokens of the text a model receives (tiktoken o200k_base). "Kept" = share of expected anchors
(caller files, symbol names, seeded fact ids) literally present in the answer.
"""
from __future__ import annotations

import argparse
import json
import random
import re
import shutil
import statistics
import subprocess
import tempfile
import time
import zipfile
from pathlib import Path

import os

import harness
from harness import RUNS, WARMUPS, tokens


def fresh_env(home: Path) -> dict:
    """harness.fresh_env plus the rustup paths serena's rust-analyzer needs (image rtok-c15:3)."""
    env = harness.fresh_env(home)
    env.update({k: os.environ[k] for k in ("RUSTUP_HOME", "CARGO_HOME") if k in os.environ})
    return env
from mcp_client import Mcp

CBM = Path("/tmp/cbm/server/codebase-memory-mcp")
CG = "/opt/c/codegraph-linux-arm64/bin/codegraph"
SERENA = ["serena", "start-mcp-server", "--project", "{w}", "--context", "claude-code",
          "--enable-web-dashboard", "false", "--agent-interface", "tools"]
SERVERS = {
    "codebase-memory-mcp": [str(CBM)],
    "codegraph": [CG, "serve", "--mcp"],
    "engram": ["/opt/c/engram", "mcp"],
    "graymatter": ["/opt/c/graymatter", "mcp", "serve", "--no-daemon"],
    "mcpproxy-go": ["/opt/c/mcpproxy", "serve", "--listen", ""],
    "serena": SERENA,
    "rtok": ["rtok", "mcp"],
}


def compact(tools: list) -> str:
    return json.dumps({"tools": tools}, separators=(",", ":"), ensure_ascii=False)


def argv_for(name: str, w: Path) -> list:
    return [a.replace("{w}", str(w)) for a in SERVERS[name]]


def one(server: str, repo: Path, setup, call, timeout: float = 900) -> tuple:
    """setup(m, w, env) -> ctx (untimed); call(m, w, ctx) -> text (timed)."""
    with tempfile.TemporaryDirectory(prefix="c15-", ignore_cleanup_errors=True) as td:
        home, w = Path(td) / "home", Path(td) / "repo"
        home.mkdir()
        shutil.copytree(repo, w, symlinks=True)
        env = fresh_env(home)
        argv = argv_for(server, w) if server in SERVERS else server(w)
        m = Mcp(argv, w, env) if argv else None
        try:
            ctx = setup(m, w, env) if setup else None
            t0 = time.perf_counter()
            text = call(m, w, ctx)
            ms = (time.perf_counter() - t0) * 1000
        finally:
            if m:
                m.close()
        if not text or not text.strip():
            raise RuntimeError("empty answer")
        if text.startswith("Error executing tool") or text.lstrip().startswith('{"available_projects"'):
            raise RuntimeError(text[:300])
        return text.replace(str(w), "<repo>"), ms


def measure(server, repo, setup, call) -> dict:
    try:
        for _ in range(WARMUPS):
            one(server, repo, setup, call)
        texts, times = [], []
        for _ in range(RUNS):
            t, ms = one(server, repo, setup, call)
            texts.append(t)
            times.append(ms)
        counts = sorted(tokens(t) for t in texts)
        spread = None if counts[-1] - counts[0] <= 2 else f"non-deterministic across runs: {counts}"
        return {"tokens": int(statistics.median(counts)), "ms": round(statistics.median(times), 1),
                "error": spread, "sample": texts[0][:1200], "full": texts[0]}
    except Exception as e:  # reported, never hidden
        return {"tokens": None, "ms": None, "error": str(e)[-300:], "sample": "", "full": ""}


def kept(text: str, anchors: list) -> float | None:
    if not anchors or not text:
        return None
    return round(100 * sum(1 for a in anchors if a in text) / len(anchors))


def emit(out: Path, row: dict) -> None:
    for r in row["results"].values():
        r.pop("full", None)
    with (out / "b3.jsonl").open("a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")
    for t, r in row["results"].items():
        print(f"{row['case']:34} raw={row['raw']} {t:22} tok={r['tokens']} kept={r.get('kept')} ms={r['ms']} {r['error'] or ''}", flush=True)


# ---------- setups ----------

def rtok_index(m, w, env):
    subprocess.run(["rtok", "graph", "index", str(w)], cwd=w, env=env, capture_output=True, check=True)


def cbm_index(m, w, env):
    res = m.call_text("index_repository", {"repo_path": str(w), "mode": "full"})
    projs = m.call_text("list_projects", {})
    mm = re.search(r'"name"\s*:\s*"([^"]+)"', projs) or re.search(r'"project"\s*:\s*"([^"]+)"', res)
    if not mm:
        raise RuntimeError(f"no project: {projs[:200]}")
    return mm.group(1)


def cg_index(m, w, env):
    subprocess.run([CG, "init", str(w)], cwd=w, env=env, capture_output=True, check=True, timeout=900)


def cg_cli(args):
    def call(m, w, ctx):
        env = fresh_env(Path(w).parent / "home")
        return subprocess.run([CG, *args], cwd=w, env=env, capture_output=True, text=True, check=True).stdout
    return call


def cbm_snippet(m, w, ctx):
    """An ambiguous name returns suggestions; pick src/plugin.rs and ask again (two calls, both counted)."""
    first = m.call_text("get_code_snippet", {"qualified_name": "estimate", "project": ctx})
    if '"ambiguous"' not in first:
        return first
    qn = next(x["qualified_name"] for x in json.loads(first)["suggestions"] if x["file_path"] == "src/plugin.rs")
    return first + "\n" + m.call_text("get_code_snippet", {"qualified_name": qn, "project": ctx})


def serena_locate(name, rel):
    def setup(m, w, env):
        txt = m.call_text("find_symbol", {"name_path_pattern": name, "relative_path": rel})
        mm = re.search(r'"name_path"\s*:\s*"([^"]+)"', txt)
        if not mm:
            raise RuntimeError(f"serena find_symbol: {txt[:200]}")
        return mm.group(1)
    return setup


# ---------- cases ----------

def catalogs(repo, out):
    for name in [n for n in SERVERS if n != "rtok"] + ["rtok"]:
        res = {}
        res["raw"] = measure(name, repo, None, lambda m, w, c: compact(m.tools()))
        if name != "rtok":
            res["rtok mcp --"] = measure(lambda w, n=name: ["rtok", "mcp", "--", *argv_for(n, w)], repo, None,
                                         lambda m, w, c: compact(m.tools()))
        n_tools = len(json.loads(res["raw"]["full"] or '{"tools":[]}')["tools"]) if res["raw"]["full"] else None
        emit(out, {"case": f"catalog:{name}", "raw": res["raw"]["tokens"], "note": f"{n_tools} tools", "results": res})


def callers(repo, out):
    for sym, rel in (("estimate", "src/plugin.rs"), ("register_mcp", "src/agents/claude/mod.rs")):
        grep = subprocess.run(["git", "grep", "-n", "-w", sym, "--", "src"], cwd=repo, capture_output=True, text=True).stdout
        files = sorted({l.split(":", 1)[0] for l in grep.splitlines() if l.endswith(".rs") or ".rs:" in l})
        anchors = [f for f in files if f.endswith(".rs")]
        res = {
            "rtok": measure("rtok", repo, rtok_index, lambda m, w, c, s=sym: m.call_text("callers", {"name": s})),
            "codebase-memory-mcp": measure("codebase-memory-mcp", repo, cbm_index, lambda m, w, c, s=sym: m.call_text(
                "trace_path", {"function_name": s, "project": c, "direction": "inbound", "depth": 1})),
            "codegraph": measure(lambda w: None, repo, cg_index, cg_cli(["callers", sym])),
            "serena": measure("serena", repo, serena_locate(sym, rel), lambda m, w, c, r=rel: m.call_text(
                "find_referencing_symbols", {"name_path": c, "relative_path": r})),
        }
        for r in res.values():
            r["kept"] = kept(r["full"], anchors)
        emit(out, {"case": f"callers:{sym}", "raw": tokens(grep), "note": f"raw = git grep -n -w {sym} -- src; kept = {len(anchors)} .rs files", "results": res})


def outline(repo, out):
    rel = "src/plugins/json_tree/mod.rs"
    src = (repo / rel).read_text()
    names = sorted(set(re.findall(r"^\s*(?:pub(?:\([^)]*\))? )?(?:fn|struct|enum|trait|const|type) ([A-Za-z_][A-Za-z0-9_]*)", src, re.M)))
    res = {
        "rtok": measure("rtok", repo, rtok_index, lambda m, w, c: m.call_text("outline", {"path": rel})),
        "codebase-memory-mcp": measure("codebase-memory-mcp", repo, cbm_index, lambda m, w, c: m.call_text(
            "get_file_outline", {"project": c, "file_path": rel})),
        "serena": measure("serena", repo, None, lambda m, w, c: m.call_text("get_symbols_overview", {"relative_path": rel})),
    }
    for r in res.values():
        r["kept"] = kept(r["full"], names)
    emit(out, {"case": f"outline:{rel}", "raw": tokens(src), "note": f"raw = whole file; kept = {len(names)} item names (fn/struct/enum/trait/const/type)", "results": res})


def symbol(repo, out):
    src = (repo / "src/plugin.rs").read_text().splitlines()
    body = "\n".join(src[180:200])
    anchors = ["fn estimate(&self, text: &str, class: Class) -> u32"]
    res = {
        "rtok": measure("rtok", repo, rtok_index, lambda m, w, c: m.call_text("symbol", {"name": "estimate"})),
        "codebase-memory-mcp": measure("codebase-memory-mcp", repo, cbm_index, cbm_snippet),
        "codegraph": measure(lambda w: None, repo, cg_index, cg_cli(["node", "estimate"])),
        "serena": measure("serena", repo, None, lambda m, w, c: m.call_text(
            "find_symbol", {"name_path_pattern": "estimate", "include_body": True})),
    }
    for r in res.values():
        r["kept"] = kept(r["full"], anchors)
    emit(out, {"case": "symbol:estimate", "raw": tokens("\n".join(src)), "note": "raw = whole src/plugin.rs; kept = an estimate signature present", "results": res})


def explore(repo, out):
    q = "wrap foreign MCP server tools list"
    anchors = ["src/mcp/wrap.rs", "tests/mcp_wrap.rs"]
    res = {
        "rtok": measure("rtok", repo, rtok_index, lambda m, w, c: m.call_text("explore", {"query": q})),
        "codegraph": measure("codegraph", repo, cg_index, lambda m, w, c: m.call_text(
            "codegraph_explore", {"query": q, "projectPath": str(w)})),
        "codebase-memory-mcp": measure("codebase-memory-mcp", repo, cbm_index, lambda m, w, c: m.call_text(
            "search_graph", {"project": c, "query": q})),
    }
    for r in res.values():
        r["kept"] = kept(r["full"], anchors)
    emit(out, {"case": "explore:" + q, "raw": 0, "note": "no raw baseline; absolute tokens", "results": res})


# ---------- memory ----------

def facts():
    rng = random.Random(1414)
    areas = ["proxy", "graph", "archive", "hooks", "mcp", "worktree", "tasks", "web", "otel", "batch"]
    verbs = ["uses", "must keep", "switched to", "dropped", "caps", "retries"]
    things = ["SQLite WAL", "zstd level 3", "a 64 KiB buffer", "tree-sitter tags", "o200k_base",
              "a 30 s timeout", "JSON lines", "TOON tables", "blake3 ids", "a single writer lock"]
    out = []
    for i in range(40):
        a, v, t = rng.choice(areas), rng.choice(verbs), rng.choice(things)
        out.append((f"F{i:03d}", f"{a} {v} {t}", f"F{i:03d}: the {a} module {v} {t} since release 0.{rng.randint(1, 17)}."))
    return out


QUERIES = [("proxy", "proxy"), ("SQLite WAL", "SQLite WAL"), ("tree-sitter", "tree-sitter tags"),
           ("timeout", "30 s timeout"), ("blake3", "blake3 ids")]


def memory(repo, out):
    fs = facts()
    want = {q: [fid for fid, _, body in fs if needle in body] for q, needle in QUERIES}
    seeds = {
        "rtok": lambda m: [m.call_text("mem_save", {"title": t, "body": b}) for _, t, b in fs],
        "engram": lambda m: [m.call_text("mem_save", {"title": t, "content": b, "capture_prompt": False}) for _, t, b in fs],
        "graymatter": lambda m: [m.call_text("memory_add", {"agent_id": "bench", "text": b}) for _, t, b in fs],
    }
    search = {
        "rtok": lambda m, q: m.call_text("mem_search", {"query": q, "limit": 10}),
        "engram": lambda m, q: m.call_text("mem_search", {"query": q, "limit": 10}),
        "graymatter": lambda m, q: m.call_text("memory_search", {"agent_id": "bench", "query": q}),
    }
    save_cost = {}
    for name in seeds:
        r = measure(name, repo, None, lambda m, w, c, n=name: "\n".join(seeds[n](m)))
        save_cost[name] = r
    emit(out, {"case": "memory:save 40 facts", "raw": sum(tokens(b) for *_, b in fs),
               "note": "raw = the 40 fact bodies; tool = total of the 40 save replies", "results": save_cost})
    for q, _ in QUERIES:
        res = {}
        for name in seeds:
            r = measure(name, repo, lambda m, w, e, n=name: seeds[n](m), lambda m, w, c, n=name, qq=q: search[n](m, qq))
            r["kept"] = kept(r["full"], want[q])
            res[name] = r
        hit = sum(len(fid) for fid in want[q])
        emit(out, {"case": f"memory:search {q!r}", "raw": sum(tokens(b) for fid, _, b in fs if fid in want[q]),
                   "note": f"raw = the {len(want[q])} matching fact bodies; kept = their ids found", "results": res})


# ---------- mcpproxy ----------

def mcpproxy(repo, out):
    def argv(w, mode="full"):
        cfg = w.parent / "mcpproxy.json"
        cfg.write_text(json.dumps({"listen": "", "data_dir": str(w.parent / "mp"), "enable_tray": False,
                                   "mcpServers": [{"name": "engram", "command": "/opt/c/engram", "args": ["mcp"],
                                                   "protocol": "stdio", "enabled": True,
                                                   "quarantined": False}]}))
        return ["/opt/c/mcpproxy", "serve", "--listen", "", "-c", str(cfg), "-d", str(w.parent / "mp"), "--log-level", "error",
                "--tool-response-mode", mode]

    def ready(m, w, env):
        for _ in range(120):
            t = m.call_text("retrieve_tools", {"query": "search memories"})
            if "mem_search" in t:
                return None
            time.sleep(1)
        raise RuntimeError("upstream engram never indexed")

    q = {"query": "search memories"}
    res = {
        "engram direct (tools/list)": measure("engram", repo, None, lambda m, w, c: compact(m.tools())),
        "rtok mcp -- engram (tools/list)": measure(lambda w: ["rtok", "mcp", "--", "/opt/c/engram", "mcp"], repo, None,
                                                   lambda m, w, c: compact(m.tools())),
        "mcpproxy tools/list": measure(argv, repo, ready, lambda m, w, c: compact(m.tools())),
        "mcpproxy tools/list + retrieve_tools": measure(argv, repo, ready, lambda m, w, c: compact(m.tools()) + "\n" + m.call_text("retrieve_tools", q)),
        "mcpproxy (compact) tools/list + retrieve_tools": measure(lambda w: argv(w, "compact"), repo, ready,
                                                                  lambda m, w, c: compact(m.tools()) + "\n" + m.call_text("retrieve_tools", q)),
    }
    emit(out, {"case": "proxy:engram behind a proxy", "raw": res["engram direct (tools/list)"]["tokens"],
               "note": "what the client loads before its first real call", "results": res})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--only", nargs="*", default=["catalogs", "callers", "outline", "symbol", "explore", "memory", "mcpproxy"])
    a = ap.parse_args()
    os.environ.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="safe.directory", GIT_CONFIG_VALUE_0="*")
    if not CBM.exists():
        zipfile.ZipFile("/opt/c/cbm.mcpb").extractall(CBM.parents[1])
        CBM.chmod(0o755)
    a.out.mkdir(parents=True, exist_ok=True)
    for k in a.only:
        globals()[k](a.repo, a.out)


if __name__ == "__main__":
    main()
