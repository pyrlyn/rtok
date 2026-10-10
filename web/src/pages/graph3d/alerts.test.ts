// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { alertRow, project } from "../../api/sampleRows";
import type { Alert, Kind } from "../../api/snapshot.gen";
import { hm } from "../format";
import { alertedIds, alertsOf, describeGroup, diffAlerts, groupAlerts, toastsFor } from "./alerts";

const a = (kind: Kind, name: string, since = 1_790_000_000): Alert => ({
  ...alertRow(kind, name, `${name} is ${kind}`),
  since,
});

describe("alert groups", () => {
  test("one group per kind, in the order of the doctor page, with the earliest check", () => {
    const groups = groupAlerts([
      a("link_broken", "z"),
      a("missing", "b", 200),
      a("missing", "c", 100),
    ]);
    expect(groups.map((g) => [g.kind, g.names, g.since])).toEqual([
      ["missing", "b, c", 100],
      ["link_broken", "z", 1_790_000_000],
    ]);
  });

  test("a single project names its cause and several name their count", () => {
    const [one, many] = [
      groupAlerts([a("missing", "b")])[0]!,
      groupAlerts([a("unreachable", "b"), a("unreachable", "c"), a("unreachable", "d")])[0]!,
    ];
    expect(describeGroup(one)).toBe(`b missing since ${hm(one.since)} (b is missing)`);
    expect(describeGroup(many)).toBe(`b, c, d unreachable since ${hm(many.since)} (3 projects)`);
  });

  test("no alerts means no groups", () => {
    expect(groupAlerts([])).toEqual([]);
  });

  test("alerts come from every row, and only alerted rows get a badge", () => {
    const rows = [
      project(1, "a", { alerts: [a("missing", "a")] }),
      project(2, "b"),
      project(3, "c", { alerts: [] }),
    ];
    expect(alertsOf(rows)).toHaveLength(1);
    expect([...alertedIds(rows)]).toEqual([1]);
  });
});

describe("raise and recover", () => {
  test("a diff finds what a snapshot added and what it dropped", () => {
    const before = [a("missing", "b"), a("link_broken", "c")];
    const after = [a("link_broken", "c"), a("unreachable", "d")];
    const d = diffAlerts(before, after);
    expect(d.raised.map((x) => x.project)).toEqual(["d"]);
    expect(d.recovered.map((x) => x.project)).toEqual(["b"]);
  });

  test("a later check of the same alert is not a new alert", () => {
    expect(diffAlerts([a("missing", "b", 1)], [a("missing", "b", 99)])).toEqual({
      raised: [],
      recovered: [],
    });
  });

  test("the same project failing in another way is a new alert", () => {
    const d = diffAlerts([a("missing", "b")], [a("backend_down", "b")]);
    expect(d.raised).toHaveLength(1);
    expect(d.recovered).toHaveLength(1);
  });

  test("toasts group by kind and direction", () => {
    const t = toastsFor(
      diffAlerts(
        [a("missing", "x")],
        [a("unreachable", "b"), a("unreachable", "c"), a("link_broken", "d")],
      ),
    );
    expect(t.map((m) => m.tone)).toEqual(["fail", "fail", "ok"]);
    expect(t[0]!.text).toMatch(/^b, c unreachable since \d\d:\d\d \(2 projects\)$/);
    expect(t[2]!.text).toBe("x missing: recovered");
  });
});
