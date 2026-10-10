// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { alertRow, project } from "../../api/sampleRows";
import type { LinkKind, ProjectRow } from "../../api/snapshot.gen";
import { Layout } from "./layout";
import { layoutInput } from "./layout";
import {
  buildScene,
  CLUSTER_ABOVE,
  colorOf,
  nodeTip,
  resolveRole,
  EDGE_WIDTH,
  radiusOf,
  scopeOf,
  signature,
  topology,
} from "./scene";

const link = (to: number, kind: LinkKind = "manual", reason: string | null = null) => ({
  kind,
  name: `p${to}`,
  reason,
  to,
});
const opts = { query: "", scopeOnly: false };

/** A -> B (auto), B -> C, A -> D (manual): the spec's four projects, A selected. */
const four = (): ProjectRow[] => [
  project(1, "A", { selected: true, links: [link(2, "auto", "Cargo path dependency"), link(4)] }),
  project(2, "B", { links: [link(3)] }),
  project(3, "C"),
  project(4, "D"),
];

describe("scene mapping", () => {
  test("one node per project and one edge per link, dashed for auto and solid for manual", () => {
    const s = buildScene(four(), opts);
    expect(s.nodes.map((n) => n.label)).toEqual(["A", "B", "C", "D"]);
    expect(s.edges.map((e) => [e.from, e.to, e.dashed])).toEqual([
      [1, 2, true],
      [1, 4, false],
      [2, 3, false],
    ]);
    expect(s.edges[0]!.reason).toBe("Cargo path dependency");
    expect(s.edges.every((e) => e.width === EDGE_WIDTH)).toBe(true);
  });

  test("counters, scope and dimming follow the selected project", () => {
    const s = buildScene(four(), opts);
    expect(s.counts).toEqual({ total: 4, inScope: 4, pairs: 3, problems: 0 });
    const rows = four().map((p) =>
      p.id === 2 ? { ...p, selected: true } : { ...p, selected: false },
    );
    const b = buildScene(rows, opts);
    expect(b.nodes.filter((n) => n.inScope).map((n) => n.label)).toEqual(["B", "C"]);
    expect(b.nodes.filter((n) => n.dim).map((n) => n.label)).toEqual(["A", "D"]);
    expect(b.edges.filter((e) => e.inScope).map((e) => e.id)).toEqual(["2>3"]);
  });

  test("nothing selected dims nothing", () => {
    const s = buildScene(
      four().map((p) => ({ ...p, selected: false })),
      opts,
    );
    expect(s.nodes.some((n) => n.dim || n.selected)).toBe(false);
  });

  test("a cycle ends the scope and a link to an unknown id is ignored", () => {
    const rows = [
      project(1, "a", { selected: true, links: [link(2), link(99)] }),
      project(2, "b", { links: [link(1)] }),
    ];
    expect([...scopeOf(rows, 1)].sort()).toEqual([1, 2]);
    expect(buildScene(rows, opts).edges).toHaveLength(2);
    expect(buildScene(rows, opts).counts.pairs).toBe(1);
    expect([...scopeOf(rows, 7)]).toEqual([]);
  });

  test("the filter hides by name or root and drops edges to hidden nodes; counters stay", () => {
    const s = buildScene(four(), { query: "d", scopeOnly: false });
    expect(s.nodes.map((n) => n.label)).toEqual(["D"]);
    expect(s.edges).toEqual([]);
    expect(s.counts.total).toBe(4);
    const scoped = buildScene(
      four().map((p) => (p.id === 2 ? { ...p, selected: true } : { ...p, selected: false })),
      { query: "", scopeOnly: true },
    );
    expect(scoped.nodes.map((n) => n.label)).toEqual(["B", "C"]);
  });

  test("a missing project is hollow and a problem; failed counts as a problem too", () => {
    const rows = [
      project(1, "a", { selected: true }),
      project(2, "b", { missing: true, state: "missing", index: null }),
      project(3, "c", { state: "failed" }),
    ];
    const s = buildScene(rows, opts);
    expect(s.nodes.map((n) => n.hollow)).toEqual([false, true, false]);
    expect(s.counts.problems).toBe(2);
  });

  test("a role colour resolves from the token and falls back without the brand stylesheet", () => {
    const style = { getPropertyValue: (n: string) => (n === "--pyr-accent-fg" ? " #5CE1FF " : "") };
    const css = style as unknown as CSSStyleDeclaration;
    expect(resolveRole("var(--pyr-accent-fg)", css, "gray")).toBe("#5CE1FF");
    expect(resolveRole("var(--pyr-delta-fg)", css, "gray")).toBe("gray");
    expect(resolveRole("#123456", css, "gray")).toBe("#123456");
  });

  test("colour is stable per root and radius grows with symbols but is capped", () => {
    expect(colorOf("/work/a")).toBe(colorOf("/work/a"));
    // Four roles are all the brand has, so only the spread is asserted, not that two roots differ.
    const roots = Array.from({ length: 24 }, (_, i) => `/work/p${i}`);
    expect(new Set(roots.map(colorOf)).size).toBeGreaterThan(2);
    expect(roots.every((r) => /^var\(--pyr-[\w-]+\)$/.test(colorOf(r)))).toBe(true);
    expect(radiusOf(undefined)).toBe(3);
    expect(radiusOf(10)).toBeLessThan(radiusOf(10_000));
    expect(radiusOf(1e30)).toBe(10);
  });

  test("above 50 projects the scene is clustered by origin", () => {
    const origins = ["manual", "session", "worktree"] as const;
    const many = Array.from({ length: CLUSTER_ABOVE + 1 }, (_, i) =>
      project(i + 1, `p${i}`, { origin: origins[i % 3] }),
    );
    const s = buildScene(many, opts);
    expect(s.clustered).toBe(true);
    expect(s.groups).toEqual(["manual", "session", "worktree"]);
    expect(s.nodes[1]!.group).toBe(1);
    expect(buildScene(many.slice(0, CLUSTER_ABOVE), opts).clustered).toBe(false);
  });

  test("the signature changes with the view, the topology only with the layout input", () => {
    const a = buildScene(four(), opts);
    const touched = buildScene(
      four().map((p) => ({ ...p, last_used_at: 9 })),
      opts,
    );
    expect(signature(touched)).toBe(signature(a));
    const moved = buildScene(
      four().map((p) => ({ ...p, selected: p.id === 3 })),
      opts,
    );
    expect(signature(moved)).not.toBe(signature(a));
    expect(topology(moved)).toBe(topology(a));
    expect(topology(buildScene(four().slice(0, 3), opts))).not.toBe(topology(a));
  });
});

describe("layout", () => {
  const settle = (l: Layout) => {
    let f = l.tick(4);
    for (let i = 0; i < 500 && !f.settled; i++) f = l.tick(4);
    return f;
  };
  const at = (f: ReturnType<Layout["tick"]>, id: number) => {
    const i = f.ids.indexOf(id);
    return Array.from(f.pos.slice(i * 3, i * 3 + 3));
  };

  test("it settles to finite positions and is deterministic", () => {
    const input = layoutInput(buildScene(four(), opts));
    const a = settle(new Layout(input));
    const b = settle(new Layout(input));
    expect(a.settled).toBe(true);
    expect([...a.pos].every(Number.isFinite)).toBe(true);
    expect([...a.pos]).toEqual([...b.pos]);
    expect(new Set(a.ids)).toEqual(new Set([1, 2, 3, 4]));
  });

  test("a settled layout does no more work", () => {
    const l = new Layout(layoutInput(buildScene(four(), opts)));
    const done = settle(l);
    expect([...l.tick(50).pos]).toEqual([...done.pos]);
  });

  test("an update keeps the nodes that stay where they were", () => {
    const l = new Layout(layoutInput(buildScene(four(), opts)));
    const before = settle(l);
    l.update(layoutInput(buildScene(four().slice(0, 3), opts)));
    const first = l.tick(1);
    const drift = at(first, 1).map((v, i) => Math.abs(v - at(before, 1)[i]!));
    expect(Math.max(...drift)).toBeLessThan(20);
    expect(first.ids).toEqual([1, 2, 3]);
  });

  test("clusters by origin end up apart from each other", () => {
    const origins = ["manual", "session", "worktree"] as const;
    const many = Array.from({ length: CLUSTER_ABOVE + 10 }, (_, i) =>
      project(i + 1, `p${i}`, { origin: origins[i % 3] }),
    );
    const s = buildScene(many, opts);
    const f = settle(new Layout(layoutInput(s)));
    const centre = (g: number) => {
      const ids = s.nodes.filter((n) => n.group === g).map((n) => n.id);
      const sum = [0, 0, 0];
      for (const id of ids) at(f, id).forEach((v, i) => (sum[i]! += v / ids.length));
      return sum;
    };
    const [c0, c1] = [centre(0) as number[], centre(1) as number[]];
    expect(Math.hypot(c0[0]! - c1[0]!, c0[1]! - c1[1]!, c0[2]! - c1[2]!)).toBeGreaterThan(100);
  });
});

describe("alert badges", () => {
  const rows = [
    project(1, "A", { selected: true, links: [link(2), link(3)] }),
    project(2, "B", { alerts: [alertRow("unreachable", "B", "share gone")] }),
    project(3, "C"),
  ];

  test("the alerted project and the edge into it are marked, nothing else is", () => {
    const s = buildScene(rows, opts);
    expect(s.nodes.map((n) => [n.label, n.alert ?? false])).toEqual([
      ["A", false],
      ["B", true],
      ["C", false],
    ]);
    expect(s.edges.map((e) => [e.to, e.alert ?? false])).toEqual([
      [2, true],
      [3, false],
    ]);
  });

  test("a snapshot that raises an alert changes the scene, so the views redraw", () => {
    const clean = rows.map((p) => ({ ...p, alerts: [] }));
    expect(signature(buildScene(rows, opts))).not.toBe(signature(buildScene(clean, opts)));
    expect(topology(buildScene(rows, opts))).toBe(topology(buildScene(clean, opts)));
  });

  test("the tooltip says alert in words", () => {
    expect(nodeTip(buildScene(rows, opts).nodes[1]!).split("\n")[0]).toBe("B · ok · alert");
  });
});
