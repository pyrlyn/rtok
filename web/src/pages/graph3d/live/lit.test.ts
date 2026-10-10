// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { project } from "../../../api/sampleRows";
import { buildScene, type Scene } from "../scene";
import { done, NOW, running, view } from "./callsFixtures";
import { accentOf, FOCUS_MS, liveState, MAX_ACCENTS } from "./lit";

const overview = buildScene([project(1, "rtok"), project(2, "ketch")], {
  query: "",
  scopeOnly: false,
});
const idOf = (name: string) => overview.nodes.find((n) => n.label === name)!.id;
const live = (...calls: ReturnType<typeof running>[]) => view({ running: calls });
const ended = (...rows: ReturnType<typeof done>[]) => view({ feed: rows });

describe("what the live canvas lights", () => {
  test("a running call lights the project node with one accent", () => {
    const { lit, busy } = liveState(overview, live(running("a")), NOW);
    expect(lit.get(idOf("rtok"))?.accents).toEqual([0]);
    expect(lit.has(idOf("ketch"))).toBe(false);
    expect(busy).toBe(0);
  });

  test("the heat window is the server's setting", () => {
    const s = { ...ended(done("a", 10, 4)), heat_window_s: 60 };
    expect(liveState(overview, s, NOW + 30_000).lit.get(idOf("rtok"))!.heat).toBeGreaterThan(0);
    expect(liveState(overview, s, NOW + 60_001).lit.size).toBe(0);
  });

  test("a finished call leaves heat that fades over five minutes and is gone after", () => {
    const s = ended(done("a", 10, 4));
    const fresh = liveState(overview, s, NOW).lit.get(idOf("rtok"))!;
    const later = liveState(overview, s, NOW + 240_000).lit.get(idOf("rtok"))!;
    expect(fresh.heat).toBeGreaterThan(later.heat);
    expect(later.heat).toBeGreaterThan(0);
    expect(liveState(overview, s, NOW + 300_001).lit.size).toBe(0);
  });

  test("a failed call is red only for a moment, an interrupted one never lights", () => {
    const s = ended(done("a", 0, 0, { ok: false, error: "no backend" }));
    expect(liveState(overview, s, NOW).lit.get(idOf("rtok"))?.failed).toBe(true);
    expect(liveState(overview, s, NOW + 6000).lit.get(idOf("rtok"))?.failed).toBe(false);
    const gone = { ...s, feed: s.feed.map((r) => ({ ...r, interrupted: true })) };
    expect(liveState(overview, gone, NOW).lit.size).toBe(0);
  });

  test("beyond eight running calls only the count grows, and slots stay distinct", () => {
    const many = Array.from({ length: 11 }, (_, i) => running(`c${i}`));
    const { lit, busy } = liveState(overview, live(...many), NOW);
    expect(lit.get(idOf("rtok"))?.accents).toEqual([...Array(MAX_ACCENTS).keys()]);
    expect(busy).toBe(3);
    const colours = [...Array(MAX_ACCENTS).keys()].map((s) => JSON.stringify(accentOf(s)));
    expect(new Set(colours).size).toBe(MAX_ACCENTS);
  });

  test("a call on a project the picture does not hold lights nothing", () => {
    const s = live(running("a", { project: "elsewhere" }), running("b", { project: null }));
    expect(liveState(overview, s, NOW).lit.size).toBe(0);
  });

  test("the drill-down lights the node named like the target, stale mark aside", () => {
    const drilled: Scene = {
      ...overview,
      label: "symbols of rtok",
      nodes: overview.nodes.map((n, i) => ({ ...n, label: i ? "⚠ store" : "open_index" })),
    };
    const lit = liveState(drilled, live(running("a", { target: "open_index" })), NOW).lit;
    expect([...lit.keys()]).toEqual([drilled.nodes[0]!.id]);
    const stale = liveState(drilled, live(running("a", { target: "store" })), NOW).lit;
    expect([...stale.keys()]).toEqual([drilled.nodes[1]!.id]);
  });
});

describe("what the camera frames", () => {
  test("a running call and a call that ended a moment ago, nothing once it is old", () => {
    const whileRunning = liveState(overview, live(running("a")), NOW);
    expect(whileRunning.focus).toEqual([idOf("rtok")]);
    const finished = ended(done("a", 10, 4, { project: "ketch" }));
    expect(liveState(overview, finished, NOW + 1000).focus).toEqual([idOf("ketch")]);
    expect(liveState(overview, finished, NOW + FOCUS_MS + 1).focus).toEqual([]);
    // Heat is still there: only the camera lets go.
    expect(
      liveState(overview, finished, NOW + FOCUS_MS + 1).lit.get(idOf("ketch"))?.heat,
    ).toBeGreaterThan(0);
  });

  test("an idle picture and a call on a project the picture lacks frame nothing", () => {
    expect(liveState(overview, view(), NOW).focus).toEqual([]);
    expect(liveState(overview, live(running("a", { project: "elsewhere" })), NOW).focus).toEqual(
      [],
    );
  });

  test("calls on two nodes frame both, in a stable order", () => {
    const both = live(running("a", { project: "ketch" }), running("b"));
    expect(liveState(overview, both, NOW).focus).toEqual(
      [idOf("rtok"), idOf("ketch")].sort((a, b) => a - b),
    );
  });
});
