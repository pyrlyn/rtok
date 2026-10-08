// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { seriesAt, type ChartSpec } from "./spec";

const calls: ChartSpec = {
  kind: "stacked-bars",
  label: "calls",
  x: ["a", "b"],
  series: [
    { id: "hook", label: "hook", values: [2, 0], tone: "accent" },
    { id: "mcp", label: "mcp", values: [0, 3], tone: "accent-soft" },
    { id: "proxy", label: "proxy", values: [1, 1], tone: "muted" },
  ],
};

describe("seriesAt", () => {
  test("finds the segment a y reading falls in, skipping zero-height ones", () => {
    expect(seriesAt(calls, 0, 1)).toBe("hook");
    expect(seriesAt(calls, 0, 2)).toBe("hook");
    expect(seriesAt(calls, 0, 2.5)).toBe("proxy");
    expect(seriesAt(calls, 1, 0.5)).toBe("mcp");
    expect(seriesAt(calls, 1, 3.5)).toBe("proxy");
  });

  test("is null above the column, on the baseline and for charts that do not stack", () => {
    expect(seriesAt(calls, 0, 3.5)).toBeNull();
    expect(seriesAt(calls, 0, 0)).toBeNull();
    expect(seriesAt(calls, 0, Number.NaN)).toBeNull();
    expect(seriesAt({ ...calls, kind: "bars" }, 0, 1)).toBeNull();
  });
});
