// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { noSavingsDays, savingsDays } from "./savingsFixtures";
import { savingsSpec } from "./SavingsTrend";

describe("Δtok savings trend (T414.13)", () => {
  test("no Measurement row anywhere is no chart, not a zero line", () => {
    expect(savingsSpec([])).toBeNull();
    expect(savingsSpec(noSavingsDays)).toBeNull();
  });

  test("the three biggest savers get a series each, the rest share one", () => {
    const spec = savingsSpec(savingsDays);
    expect(spec?.kind).toBe("stacked-bars");
    expect(spec?.series.map((s) => [s.label, s.tone])).toEqual([
      ["shell", "delta"],
      ["read", "accent"],
      ["archive", "accent-soft"],
      ["2 other", "muted"],
    ]);
    expect(spec?.x[0]).toBe("09-25");
    expect(spec?.titles?.[13]).toBe("2026-10-08 · 14 rows");
  });

  test("a day without rows is a gap in every series; a plugin without rows that day too", () => {
    const spec = savingsSpec(savingsDays);
    const at = (i: number) => spec?.series.map((s) => s.values[i]);
    expect(at(2)).toEqual([null, null, null, null]);
    // 2026-09-28: shell and graph only, graph inside `other`.
    expect(at(3)).toEqual([5_000, null, null, 900]);
    // 2026-10-04: archive's expand cost nets its day below zero; it stays a value.
    expect(at(9)).toEqual([8_800, null, -400, null]);
    expect(spec?.label).toContain("11 of 14 days with Measurement rows");
  });
});
