// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type {
  DiffDef,
  DiffProject,
  DrillEdge,
  DrillGraph,
  DrillNode,
} from "../../api/snapshot.gen";
import type { PillTone } from "../../ui/Pill";

/** What a change did to a symbol (T329 §8e). A rename is a change of the new name. */
export type Change = "added" | "removed" | "changed" | "moved";

export const CHANGES: readonly Change[] = ["added", "removed", "changed", "moved"];

/** Brand roles, so both themes and the 3D stage (`resolveRole`) take their own value. */
export const CHANGE_ROLE: Record<Change, string> = {
  added: "var(--pyr-success-fg)",
  removed: "var(--pyr-danger-fg)",
  changed: "var(--pyr-warn-fg)",
  moved: "var(--pyr-accent-fg)",
};

export const CHANGE_TONE: Record<Change, PillTone> = {
  added: "ok",
  removed: "fail",
  changed: "warn",
  moved: "info",
};

/** The text cue beside the colour: a node label, a list row and a tooltip all say it. */
export const CHANGE_MARK: Record<Change, string> = {
  added: "+",
  removed: "−",
  changed: "~",
  moved: "→",
};

export interface Marked {
  /** The frame plus a ghost for each removed symbol of an expanded file; the live graph is untouched. */
  graph: DrillGraph;
  nodes: ReadonlyMap<string, Change>;
  edges: ReadonlyMap<string, Change>;
}

export const edgeId = (e: Pick<DrillEdge, "kind" | "from" | "to">) => `${e.kind}:${e.from}>${e.to}`;

const TYPES = new Set(["class", "struct", "enum", "interface", "trait", "type", "union", "impl"]);
const MODULES = new Set(["module", "namespace", "package"]);

/** The node kind the server gives a definition of this kind (`node_kind` in drill.rs). */
const nodeKind = (kind: string): DrillNode["kind"] =>
  TYPES.has(kind) ? "type" : MODULES.has(kind) ? "module" : "function";

const key = (path: string, name: string) => `${path}\0${name}`;

/**
 * Lays the server's diff over a drill-down frame: symbols the frame draws take their change, a file
 * takes the one change of its symbols (several kinds make it "changed"), and a removed symbol of an
 * expanded file is drawn as a ghost, since the working tree no longer has it. Edges are matched
 * only where both ends are drawn as symbols; the side panel lists every edge either way.
 */
export function markGraph(g: DrillGraph, d: DiffProject | undefined, expanded: string[]): Marked {
  const nodes = new Map<string, Change>();
  const edges = new Map<string, Change>();
  if (!d) return { graph: g, nodes, edges };

  const byDef = new Map<string, Change>();
  const inFile = new Map<string, Set<Change>>();
  const note = (def: DiffDef, change: Change) => {
    byDef.set(key(def.path, def.name), change);
    inFile.set(def.path, (inFile.get(def.path) ?? new Set()).add(change));
  };
  for (const x of d.added) note(x, "added");
  for (const x of d.changed) note(x, "changed");
  for (const x of d.renamed) note(x.to, "changed");
  for (const x of d.moved) note(x.to, "moved");
  for (const x of d.removed) inFile.set(x.path, (inFile.get(x.path) ?? new Set()).add("removed"));

  const symbols = g.nodes.filter((n) => n.kind !== "file" && n.project === g.project);
  for (const n of g.nodes) {
    const change =
      n.kind === "file"
        ? summary(inFile.get(n.path))
        : n.project === g.project
          ? byDef.get(key(n.path, n.label))
          : undefined;
    if (change) nodes.set(n.id, change);
  }

  const ghosts: DrillNode[] = d.removed
    .filter((x) => expanded.includes(x.path))
    .map((x) => ({
      id: `d:${x.path}:${x.line}:${x.name}`,
      kind: nodeKind(x.kind),
      label: x.name,
      path: x.path,
      line: x.line,
      project: g.project,
      signature: "",
      weight: 0,
      stale: false,
    }));
  for (const x of ghosts) nodes.set(x.id, "removed");
  const ghostEdges: DrillEdge[] = ghosts.map((x) => ({
    from: `f:${x.path}`,
    to: x.id,
    kind: "contains",
    count: 1,
  }));

  // A caller is its symbol when the frame draws it, else the file that holds it.
  const caller = (path: string, scope: string) =>
    symbols.find((n) => n.path === path && n.label === scope)?.id ?? `f:${path}`;
  const callee = (name: string) => {
    const named = [...symbols, ...ghosts].filter((n) => n.label === name);
    return named.length === 1 ? named[0]!.id : undefined;
  };
  for (const e of d.edges_removed) {
    const to = callee(e.name);
    const from = caller(e.path, e.scope);
    if (to && [...g.nodes, ...ghosts].some((n) => n.id === from)) {
      const edge: DrillEdge = { from, to, kind: "calls", count: 1 };
      ghostEdges.push(edge);
      edges.set(edgeId(edge), "removed");
    }
  }
  for (const e of d.edges_added) {
    const to = callee(e.name);
    const edge = g.edges.find(
      (x) => x.kind === "calls" && x.from === caller(e.path, e.scope) && x.to === to,
    );
    if (edge) edges.set(edgeId(edge), "added");
  }
  return {
    graph: { ...g, nodes: [...g.nodes, ...ghosts], edges: [...g.edges, ...ghostEdges] },
    nodes,
    edges,
  };
}

const summary = (set: Set<Change> | undefined): Change | undefined =>
  !set ? undefined : set.size === 1 ? [...set][0] : "changed";
