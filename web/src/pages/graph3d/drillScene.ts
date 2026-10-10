// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { DrillGraph, DrillNodeKind } from "../../api/snapshot.gen";
import { colorOf, EDGE_WIDTH, radiusOf, type Scene, type SceneEdge, type Shape } from "./scene";

const SHAPES: Record<DrillNodeKind, Shape> = {
    file: "cube",
    type: "octahedron",
    module: "octahedron",
    function: "sphere",
    external: "sphere",
};

const dirOf = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf("/")));

export interface DrillSceneOptions {
    /** A numeric id that stays the same for a string id across frames, so the layout keeps positions. */
    idOf(id: string): number;
    /** Project id to root, for the colour of a node that belongs to a linked project. */
    roots: ReadonlyMap<number, string>;
    selected: string | null;
}

/**
 * The frame as the scene the overview already draws (T329.22), so the layout, both canvases and
 * the list are reused. Directories become the layout's groups; an edge to a node the cap left
 * out is dropped, since the "+N more" count already says something is missing.
 */
export function drillScene(g: DrillGraph, o: DrillSceneOptions): Scene {
    const dirs = [...new Set(g.nodes.map((n) => dirOf(n.path)))].sort();
    const ids = new Set(g.nodes.map((n) => n.id));
    const nodes = g.nodes.map((n) => ({
        id: o.idOf(n.id),
        label: n.stale ? `⚠ ${n.label}` : n.label,
        root: n.path,
        color: colorOf(o.roots.get(n.project) ?? g.root),
        radius: radiusOf(n.weight),
        shape: SHAPES[n.kind],
        state: n.stale ? "stale" : n.kind,
        origin: "manual" as const,
        // A symbol of another project is an outline: it is drawn here, but it lives elsewhere.
        hollow: n.kind === "external",
        selected: n.id === o.selected,
        inScope: true,
        dim: false,
        group: dirs.indexOf(dirOf(n.path)),
    }));
    const edges = g.edges
        .filter((e) => ids.has(e.from) && ids.has(e.to))
        .map<SceneEdge>((e) => ({
            id: `${e.kind}:${e.from}>${e.to}`,
            from: o.idOf(e.from),
            to: o.idOf(e.to),
            kind: e.kind,
            dashed: e.kind === "imports",
            width: EDGE_WIDTH * (1 + Math.min(2, Math.log10(e.count || 1))),
            reason: e.count > 1 ? `×${e.count}` : null,
            inScope: true,
        }));
    return {
        nodes,
        edges,
        clustered: dirs.length > 1,
        groups: dirs,
        label: `symbols of ${g.name}`,
        counts: {
            total: nodes.length,
            inScope: nodes.length,
            pairs: edges.length,
            problems: g.nodes.filter((n) => n.stale).length,
        },
    };
}

/** What the canvas holds before a frame arrives or when the frame has nothing to draw. */
export const emptyScene: Scene = {
    nodes: [],
    edges: [],
    clustered: false,
    groups: [],
    label: "symbols",
    counts: { total: 0, inScope: 0, pairs: 0, problems: 0 },
};
