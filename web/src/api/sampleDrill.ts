// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// A stand-in for the server's `graph` answer (T329.14), for `?sample`, stories and tests: a few
// real-looking files per project and one generated project large enough to need "+N more".
import type {
    DrillEdge,
    DrillGraph,
    DrillHit,
    DrillNode,
    DrillRequest,
    ProjectRow,
    Snapshot,
} from "./snapshot.gen";
import type { Connect, Frame } from "./ws";

type Def = [name: string, kind: "function" | "type", line: number, calls: string[]];

/** Calls name a definition of the same project, or `project:name` for one of a linked project. */
const FILES: Record<string, Record<string, Def[]>> = {
    rtok: {
        "src/main.rs": [["main", "function", 3, ["run_hook", "open_store"]]],
        "src/hook.rs": [["run_hook", "function", 10, ["open_store"]]],
        "src/plugins/graph/drill.rs": [
            ["Model", "type", 20, []],
            ["run", "function", 40, ["load", "resolve"]],
            ["load", "function", 60, ["Model"]],
            ["resolve", "function", 80, []],
        ],
        "src/store.rs": [["open_store", "function", 5, ["ketch:open_index"]]],
    },
    ketch: { "src/lib.rs": [["open_index", "function", 7, []]] },
};

/** 600 files in a chain: the first answer is cut at the default 500 nodes. */
export const BIG = "big";
const generated = (): Record<string, Def[]> =>
    Object.fromEntries(
        Array.from({ length: 600 }, (_, i) => [
            `src/m${i % 20}/f${i}.rs`,
            [[`fn_${i}`, "function", 1, i < 599 ? [`fn_${i + 1}`] : []] satisfies Def],
        ]),
    );

interface Def2 {
    path: string;
    name: string;
    kind: string;
    line: number;
    calls: string[];
}

const flat = (files: Record<string, Def[]>): Def2[] =>
    Object.entries(files).flatMap(([path, defs]) =>
        defs.map(([name, kind, line, calls]) => ({ path, name, kind, line, calls })),
    );

/** The frame the server would send, or throws what its refusal says for an unknown project. */
export function sampleDrill(req: DrillRequest, rows: ProjectRow[]): DrillGraph {
    const row = rows.find((r) => String(r.id) === req.project || r.root === req.project);
    if (!row) throw new Error(`unknown project ${req.project}`);
    const g: DrillGraph = {
        project: row.id,
        name: row.name,
        root: row.root,
        state: "ok",
        nodes: [],
        edges: [],
        more: 0,
        partial: (row.index?.pending ?? 0) > 0,
        hits: [],
    };
    if (row.missing) return { ...g, state: "missing" };
    if (!row.index) return { ...g, state: "not indexed" };

    const own = row.name.startsWith(BIG) ? generated() : (FILES[row.name] ?? {});
    const defs = flat(own);
    const other = (name: string) => rows.find((r) => r.name === name);
    const q = req.query.trim().toLowerCase();
    g.hits = q
        ? [row, ...row.links.flatMap((l) => rows.filter((r) => r.id === l.to))].flatMap((r) =>
              flat(FILES[r.name] ?? {})
                  .filter((d) => d.name.toLowerCase().includes(q))
                  .map<DrillHit>((d) => ({ project: r.id, name: d.name, path: d.path, kind: d.kind, line: d.line })),
          )
        : [];

    const focus = req.focus && defs.find((d) => d.path === req.focus!.path && d.name === req.focus!.name);
    let shown = new Set(defs.filter((d) => req.expand.includes(d.path)));
    if (focus) {
        shown = new Set([focus]);
        for (let i = 0; i < (req.depth ?? 1); i++) {
            for (const d of defs) {
                const reaches = (a: Def2, b: Def2) => a.calls.includes(b.name);
                if ([...shown].some((s) => reaches(s, d) || reaches(d, s))) shown.add(d);
            }
        }
    }
    const sym = (d: Def2) => `s:${d.path}:${d.line}:${d.name}`;
    const end = (d: Def2) => (shown.has(d) ? sym(d) : `f:${d.path}`);
    const ends = new Map<string, DrillNode>();
    const edges = new Map<string, DrillEdge>();
    const node = (id: string, kind: DrillNode["kind"], d: Def2, project = row.id): DrillNode => {
        const have = ends.get(id);
        if (have) return have;
        const n: DrillNode = {
            id,
            kind,
            label: kind === "file" ? d.path.split("/").at(-1)! : d.name,
            path: d.path,
            line: kind === "file" ? 0 : d.line,
            project,
            signature: kind === "file" ? "" : `fn ${d.name}()`,
            stale: false,
            weight: 0,
        };
        ends.set(id, n);
        return n;
    };
    const link = (from: DrillNode, to: DrillNode, kind: DrillEdge["kind"]) => {
        if (from.id === to.id) return;
        const key = `${kind}:${from.id}>${to.id}`;
        const e = edges.get(key) ?? { from: from.id, to: to.id, kind, count: 0 };
        e.count += 1;
        edges.set(key, e);
    };
    const kindOf = (d: Def2) => (d.kind === "type" ? "type" : "function");
    const drawn = (d: Def2) => (shown.has(d) ? node(sym(d), kindOf(d), d) : node(end(d), "file", d));
    for (const d of defs) {
        for (const c of d.calls) {
            const [pn, name] = c.includes(":") ? (c.split(":") as [string, string]) : [row.name, c];
            const target = pn === row.name ? defs.find((t) => t.name === name) : undefined;
            if (focus && !shown.has(d)) continue;
            if (target && (!focus || shown.has(target))) link(drawn(d), drawn(target), "calls");
            const there = pn !== row.name && other(pn);
            const ext = there && flat(FILES[pn] ?? {}).find((t) => t.name === name);
            if (ext && there && (!focus || shown.has(d))) {
                link(drawn(d), node(`x:${there.id}:${ext.path}:${ext.name}`, "external", ext, there.id), "calls");
            }
        }
    }
    for (const d of shown) link(node(`f:${d.path}`, "file", d), drawn(d), "contains");
    if (!focus) for (const d of defs) node(`f:${d.path}`, "file", d);

    for (const e of edges.values()) for (const id of [e.from, e.to]) ends.get(id)!.weight += e.count;
    const nodes = [...ends.values()].sort(
        (a, b) =>
            Number(b.id.startsWith("s:")) - Number(a.id.startsWith("s:")) ||
            b.weight - a.weight ||
            a.id.localeCompare(b.id),
    );
    const limit = req.limit ?? 500;
    const kept = new Set(nodes.slice(0, limit).map((n) => n.id));
    return {
        ...g,
        nodes: nodes.slice(0, limit),
        more: Math.max(0, nodes.length - limit),
        edges: [...edges.values()].filter((e) => kept.has(e.from) && kept.has(e.to)),
    };
}

/** The server's reply to a graph request: the frame, or the refusal message it sends for an unknown project. */
export function drillReply(req: DrillRequest, rows: ProjectRow[]): Frame {
    try {
        return { type: "graph", graph: sampleDrill(req, rows) };
    } catch (e) {
        return { type: "message", text: e instanceof Error ? e.message : String(e) };
    }
}

/** A connection that serves one snapshot and answers graph requests from `sampleDrill`. */
export const drillServer =
    (snapshot: Snapshot): Connect =>
    (h) => {
        h.onState("open");
        h.onFrame({ type: "snapshot", snapshot });
        return {
            send(m) {
                if ("graph" in m) queueMicrotask(() => h.onFrame(drillReply(m.graph, snapshot.projects ?? [])));
                return true;
            },
            close() {},
        };
    };
