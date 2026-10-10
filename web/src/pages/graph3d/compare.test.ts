// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { sampleDiff } from "../../api/sampleDiff";
import { sampleDrill } from "../../api/sampleDrill";
import { project } from "../../api/sampleRows";
import type { DiffProject, DrillGraph } from "../../api/snapshot.gen";
import { CHANGE_MARK, CHANGES, markGraph } from "./compare";
import { drillScene } from "./drillScene";

const rows = [project(1, "rtok"), project(2, "ketch")];
const DRILL = "src/plugins/graph/drill.rs";
const HOOK = "src/hook.rs";

const frame = (expand: string[]): DrillGraph =>
  sampleDrill({ project: "1", expand, focus: null, depth: null, limit: null, query: "" }, rows);
const diff = (): DiffProject =>
  sampleDiff({ project: "1", from: [], to: null, export: null }, rows).projects[0]!;
const id = (g: DrillGraph, label: string) => g.nodes.find((n) => n.label === label)!.id;

describe("markGraph", () => {
  test("a symbol takes its change: amber, green, blue, and a file the one change it holds", () => {
    const g = frame([DRILL, "src/store.rs"]);
    const m = markGraph(g, diff(), [DRILL, "src/store.rs"]);
    const of = (label: string) => m.nodes.get(id(m.graph, label));
    expect([of("run"), of("load"), of("resolve"), of("open_store")]).toEqual([
      "changed",
      "changed",
      "added",
      "moved",
    ]);
    // drill.rs holds changed and added symbols: mixed kinds read as changed.
    expect(m.nodes.get("f:" + DRILL)).toBe("changed");
    expect(m.nodes.get("f:src/store.rs")).toBe("moved");
    expect(m.nodes.has(id(m.graph, "Model"))).toBe(false);
  });

  test("a removed symbol is a ghost only where its file is expanded, and the live frame is untouched", () => {
    const g = frame([HOOK]);
    const before = JSON.stringify(g);
    const open = markGraph(g, diff(), [HOOK]);
    const ghost = open.graph.nodes.find((n) => n.label === "legacy_hook")!;
    expect(open.nodes.get(ghost.id)).toBe("removed");
    expect(open.graph.edges).toContainEqual({
      from: `f:${HOOK}`,
      to: ghost.id,
      kind: "contains",
      count: 1,
    });
    // The call that went away is a red edge from run_hook to the ghost.
    expect([...open.edges.values()]).toEqual(["removed"]);
    const closed = markGraph(frame([]), diff(), []);
    expect(closed.graph.nodes.some((n) => n.label === "legacy_hook")).toBe(false);
    expect(closed.nodes.get(`f:${HOOK}`)).toBe("removed");
    expect(JSON.stringify(g)).toBe(before);
  });

  test("an added call is matched to the edge the frame already draws", () => {
    const g = frame([DRILL]);
    const m = markGraph(g, diff(), [DRILL]);
    const added = [...m.edges].filter(([, c]) => c === "added");
    expect(added).toEqual([[`calls:${id(g, "run")}>${id(g, "resolve")}`, "added"]]);
  });

  test("no diff leaves the frame as it is", () => {
    const g = frame([]);
    const m = markGraph(g, undefined, []);
    expect(m.graph).toBe(g);
    expect(m.nodes.size + m.edges.size).toBe(0);
  });
});

describe("compare scene", () => {
  test("each change has its own colour, mark and outline, and an edge carries its tone", () => {
    const g = frame([HOOK, DRILL]);
    const m = markGraph(g, diff(), [HOOK, DRILL]);
    const scene = drillScene(m.graph, {
      idOf: (
        (ids) => (key: string) =>
          ids.get(key) ?? (ids.set(key, ids.size + 1), ids.size)
      )(new Map<string, number>()),
      roots: new Map(),
      selected: null,
      changes: m,
    });
    const node = (label: string) => scene.nodes.find((n) => n.label.endsWith(label))!;
    const colours = CHANGES.map((c) =>
      scene.nodes.find((n) => n.label.startsWith(CHANGE_MARK[c]))!,
    );
    expect(new Set(colours.map((n) => n.color)).size).toBe(CHANGES.length);
    expect(node("run").label).toBe("~ run");
    expect(node("resolve").label).toBe("+ resolve");
    expect(node("legacy_hook")).toMatchObject({ label: "− legacy_hook", hollow: true });
    expect(node("legacy_hook").state).toBe("function · removed");
    expect(scene.edges.find((e) => e.reason === "removed")).toMatchObject({
      dashed: true,
      tone: "var(--pyr-danger-fg)",
    });
    expect(scene.edges.find((e) => e.reason === "added")?.tone).toBe("var(--pyr-success-fg)");
  });
});
