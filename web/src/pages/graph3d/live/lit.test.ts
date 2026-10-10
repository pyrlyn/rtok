// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { project } from "../../../api/sampleRows";
import { buildScene, type Scene } from "../scene";
import { type CallsStore, emptyStore, fold } from "./callsStore";
import { batch, end, event } from "./callsFixtures";
import { accentOf, liveState, MAX_ACCENTS } from "./lit";

const NOW = 1_000_000;
const overview = buildScene([project(1, "rtok"), project(2, "ketch")], {
  query: "",
  scopeOnly: false,
});
const idOf = (name: string) => overview.nodes.find((n) => n.label === name)!.id;
const store = (...events: Parameters<typeof batch>[0]) => fold(emptyStore, batch(events), NOW);

describe("what the live canvas lights", () => {
  test("a running call lights the project node with one accent", () => {
    const { lit, busy } = liveState(overview, store(event({ call: "a" })), NOW);
    expect(lit.get(idOf("rtok"))?.accents).toEqual([0]);
    expect(lit.has(idOf("ketch"))).toBe(false);
    expect(busy).toBe(0);
  });

  test("a finished call leaves heat that fades over five minutes and is gone after", () => {
    const s = store(end("a", 10, 4));
    const fresh = liveState(overview, s, NOW).lit.get(idOf("rtok"))!;
    const later = liveState(overview, s, NOW + 240_000).lit.get(idOf("rtok"))!;
    expect(fresh.heat).toBeGreaterThan(later.heat);
    expect(later.heat).toBeGreaterThan(0);
    expect(liveState(overview, s, NOW + 300_001).lit.size).toBe(0);
  });

  test("a failed call is red only for a moment, an interrupted one never lights", () => {
    const s = store(end("a", 0, 0, { ok: false, error: "no backend" }));
    expect(liveState(overview, s, NOW).lit.get(idOf("rtok"))?.failed).toBe(true);
    expect(liveState(overview, s, NOW + 6000).lit.get(idOf("rtok"))?.failed).toBe(false);
    const gone: CallsStore = { ...s, feed: s.feed.map((r) => ({ ...r, interrupted: true })) };
    expect(liveState(overview, gone, NOW).lit.size).toBe(0);
  });

  test("beyond eight running calls only the count grows, and slots stay distinct", () => {
    const many = Array.from({ length: 11 }, (_, i) => event({ call: `c${i}` }));
    const { lit, busy } = liveState(overview, store(...many), NOW);
    expect(lit.get(idOf("rtok"))?.accents).toEqual([...Array(MAX_ACCENTS).keys()]);
    expect(busy).toBe(3);
    const colours = [...Array(MAX_ACCENTS).keys()].map((s) => JSON.stringify(accentOf(s)));
    expect(new Set(colours).size).toBe(MAX_ACCENTS);
  });

  test("a call on a project the picture does not hold lights nothing", () => {
    const s = store(event({ project: "elsewhere" }), event({ call: "b", project: null }));
    expect(liveState(overview, s, NOW).lit.size).toBe(0);
  });

  test("the drill-down lights the node named like the target, stale mark aside", () => {
    const drilled: Scene = {
      ...overview,
      label: "symbols of rtok",
      nodes: overview.nodes.map((n, i) => ({ ...n, label: i ? "⚠ store" : "open_index" })),
    };
    const lit = liveState(drilled, store(event({ target: "open_index" })), NOW).lit;
    expect([...lit.keys()]).toEqual([drilled.nodes[0]!.id]);
    const stale = liveState(drilled, store(event({ target: "store" })), NOW).lit;
    expect([...stale.keys()]).toEqual([drilled.nodes[1]!.id]);
  });
});
