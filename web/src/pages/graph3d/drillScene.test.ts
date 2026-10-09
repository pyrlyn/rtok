// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import type { DrillGraph, DrillNode } from "../../api/snapshot.gen";
import { drillScene, emptyScene } from "./drillScene";
import { colorOf, edgeHow, signature, topology } from "./scene";

const node = (id: string, kind: DrillNode["kind"], over: Partial<DrillNode> = {}): DrillNode => ({
    id,
    kind,
    label: id,
    path: "src/a.rs",
    line: 1,
    project: 1,
    signature: "",
    stale: false,
    weight: 2,
    ...over,
});

const frame = (over: Partial<DrillGraph> = {}): DrillGraph => ({
    project: 1,
    name: "rtok",
    root: "/work/rtok",
    state: "ok",
    nodes: [
        node("f:src/a.rs", "file"),
        node("f:lib/b.rs", "file", { path: "lib/b.rs" }),
        node("s:src/a.rs:1:run", "function", { label: "run" }),
        node("s:src/a.rs:9:Model", "type", { label: "Model" }),
        node("x:2:k.rs:open", "external", { label: "open", path: "k.rs", project: 2 }),
    ],
    edges: [
        { from: "f:src/a.rs", to: "f:lib/b.rs", kind: "imports", count: 1 },
        { from: "s:src/a.rs:1:run", to: "x:2:k.rs:open", kind: "calls", count: 3 },
    ],
    more: 0,
    partial: false,
    hits: [],
    ...over,
});

const options = (selected: string | null = null) => {
    const ids = new Map<string, number>();
    return {
        idOf: (id: string) => ids.get(id) ?? (ids.set(id, ids.size + 1), ids.size),
        roots: new Map([
            [1, "/work/rtok"],
            [2, "/work/ketch"],
        ]),
        selected,
    };
};

describe("frame to scene", () => {
    test("a shape per kind and a hollow outline for a symbol of another project", () => {
        const s = drillScene(frame(), options());
        expect(s.nodes.map((n) => [n.label, n.shape, n.hollow])).toEqual([
            ["f:src/a.rs", "cube", false],
            ["f:lib/b.rs", "cube", false],
            ["run", "sphere", false],
            ["Model", "octahedron", false],
            ["open", "sphere", true],
        ]);
    });

    test("a node is drawn in the colour of the project that owns it", () => {
        const s = drillScene(frame(), options());
        expect(s.nodes[0]!.color).toBe(colorOf("/work/rtok"));
        expect(s.nodes[4]!.color).toBe(colorOf("/work/ketch"));
        // A project the registry does not hold falls back to the frame's own root.
        const lone = drillScene(frame(), { ...options(), roots: new Map() });
        expect(lone.nodes[4]!.color).toBe(colorOf("/work/rtok"));
    });

    test("directories are the layout's groups", () => {
        const s = drillScene(frame(), options());
        expect(s.clustered).toBe(true);
        expect(s.groups).toEqual(["", "lib", "src"]);
        const [a, b] = s.nodes;
        expect(a!.group).toBe(2);
        expect(b!.group).toBe(1);
        const flat = drillScene(frame({ nodes: [node("f:x", "file")], edges: [] }), options());
        expect(flat.clustered).toBe(false);
    });

    test("an import is dashed and a call count widens the edge and names itself", () => {
        const s = drillScene(frame(), options());
        const [imp, call] = s.edges;
        expect([imp!.dashed, imp!.reason, edgeHow(imp!)]).toEqual([true, null, "imports"]);
        expect([call!.dashed, call!.reason, edgeHow(call!)]).toEqual([false, "×3", "calls ×3"]);
        expect(call!.width).toBeGreaterThan(imp!.width);
    });

    test("an edge to a node the cap left out is dropped", () => {
        const s = drillScene(
            frame({ nodes: [node("f:src/a.rs", "file")], more: 4 }),
            options(),
        );
        expect(s.edges).toEqual([]);
    });

    test("a stale file is marked in its label and state, and counted as a problem", () => {
        const s = drillScene(frame({ nodes: [node("f:x", "file", { stale: true, label: "x.rs" })], edges: [] }), options());
        expect(s.nodes[0]).toMatchObject({ label: "⚠ x.rs", state: "stale" });
        expect(s.counts.problems).toBe(1);
    });

    test("an id keeps its number across frames, so the layout keeps its position", () => {
        const o = options();
        const first = drillScene(frame(), o);
        const next = drillScene(
            frame({ nodes: [node("s:new", "function"), ...frame().nodes] }),
            o,
        );
        const idOf = (s: typeof first, label: string) => s.nodes.find((n) => n.label === label)!.id;
        expect(idOf(next, "run")).toBe(idOf(first, "run"));
        expect(idOf(next, "s:new")).not.toBe(idOf(first, "run"));
    });

    test("selecting a node changes what is drawn but not what the layout depends on", () => {
        const o = options();
        const plain = drillScene(frame(), o);
        const picked = drillScene(frame(), { ...o, selected: "f:src/a.rs" });
        expect(picked.nodes[0]!.selected).toBe(true);
        expect(signature(picked)).not.toBe(signature(plain));
        expect(topology(picked)).toBe(topology(plain));
    });

    test("the empty scene has nothing to lay out", () => {
        expect(emptyScene.nodes).toEqual([]);
        expect(drillScene(frame({ nodes: [], edges: [] }), options()).counts.total).toBe(0);
    });
});
