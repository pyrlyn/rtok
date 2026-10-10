// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { DrillGraph, DrillNodeKind } from "../../api/snapshot.gen";
import { CHANGE_MARK, CHANGE_ROLE, type Change, edgeId } from "./compare";
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
  /** Projects with an alert (T329.25): their outlines in this frame and the edges to them are badged. */
  alerted?: ReadonlySet<number>;
  /** Compare mode (T329.35): the change of a node or an edge, by id. Colour is never the only cue. */
  changes?: { nodes: ReadonlyMap<string, Change>; edges: ReadonlyMap<string, Change> };
}

/**
 * The frame as the scene the overview already draws (T329.22), so the layout, both canvases and
 * the list are reused. Directories become the layout's groups; an edge to a node the cap left
 * out is dropped, since the "+N more" count already says something is missing.
 */
export function drillScene(g: DrillGraph, o: DrillSceneOptions): Scene {
  const dirs = [...new Set(g.nodes.map((n) => dirOf(n.path)))].sort();
  const ids = new Set(g.nodes.map((n) => n.id));
  const alert = (project: number) => o.alerted?.has(project) ?? false;
  const down = new Set(
    g.nodes.filter((n) => n.kind === "external" && alert(n.project)).map((n) => n.id),
  );
  const nodes = g.nodes.map((n) => {
    const change = o.changes?.nodes.get(n.id);
    return {
      id: o.idOf(n.id),
      label: `${change ? `${CHANGE_MARK[change]} ` : ""}${n.stale ? `⚠ ${n.label}` : n.label}`,
      root: n.path,
      color: change ? CHANGE_ROLE[change] : colorOf(o.roots.get(n.project) ?? g.root),
      radius: radiusOf(n.weight),
      shape: SHAPES[n.kind],
      state: [n.stale ? "stale" : n.kind, change].filter(Boolean).join(" · "),
      origin: "manual" as const,
      // A symbol of another project is an outline: it is drawn here, but it lives elsewhere. A
      // removed one is an outline too, since the working tree no longer holds it.
      hollow: n.kind === "external" || change === "removed",
      selected: n.id === o.selected,
      inScope: true,
      dim: false,
      group: dirs.indexOf(dirOf(n.path)),
      ...(down.has(n.id) && { alert: true }),
    };
  });
  const edges = g.edges
    .filter((e) => ids.has(e.from) && ids.has(e.to))
    .map<SceneEdge>((e) => {
      const change = o.changes?.edges.get(edgeId(e));
      return {
        id: edgeId(e),
        from: o.idOf(e.from),
        to: o.idOf(e.to),
        kind: e.kind,
        dashed: e.kind === "imports" || change === "removed",
        width: EDGE_WIDTH * (1 + Math.min(2, Math.log10(e.count || 1))),
        reason: [e.count > 1 ? `×${e.count}` : "", change ?? ""].filter(Boolean).join(" ") || null,
        inScope: true,
        ...(change && { tone: CHANGE_ROLE[change] }),
        ...(down.has(e.to) && { alert: true }),
      };
    });
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
