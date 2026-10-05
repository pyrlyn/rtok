// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";
import { toOption, topAt, type Palette } from "./echarts";
import type { ChartSpec } from "./spec";

const p: Palette = {
  accent: "#0aa",
  muted: "#777",
  subtle: "#999",
  delta: "#f60",
  border: "#ddd",
  font: "mono",
};

const calls: ChartSpec = {
  kind: "stacked-bars",
  axes: true,
  label: "calls",
  x: ["a", "b", "c"],
  series: [
    { id: "hook", label: "hook", values: [1, 0, 5], tone: "accent" },
    { id: "mcp", label: "mcp", values: [2, 0, 1], tone: "accent-soft" },
  ],
  dots: { id: "failed", label: "failed", values: [0, 0, 2], tone: "delta" },
};

describe("toOption", () => {
  test("stacks bars, rounds the scale up to four even ticks and puts dots on the stack top", () => {
    const o = toOption(calls, p);
    expect(o.yAxis).toMatchObject({ show: true, min: 0, max: 8, interval: 2 });
    const [hook, mcp, dots] = o.series;
    expect(hook).toMatchObject({ type: "bar", stack: "all", data: [1, 0, 5] });
    expect(mcp).toMatchObject({ type: "bar", stack: "all", itemStyle: { opacity: 0.5 } });
    expect(dots).toMatchObject({ type: "scatter", data: [null, null, 6] });
  });

  test("a mini line has no axes and scales to its own range", () => {
    const o = toOption(
      {
        kind: "line",
        label: "l",
        x: ["1", "2"],
        series: [{ id: "v", label: "v", values: [3, 9], tone: "accent" }],
      },
      p,
    );
    expect(o.xAxis).toMatchObject({ show: false, boundaryGap: false });
    expect(o.yAxis).toMatchObject({ show: false, min: "dataMin", max: "dataMax" });
    expect(o.tooltip).toMatchObject({ showContent: false, axisPointer: { type: "line" } });
  });

  test("mini bars fade low bars and never divide by zero", () => {
    const o = toOption(
      {
        kind: "bars",
        label: "b",
        x: ["1", "2"],
        series: [{ id: "v", label: "v", values: [0, 0], tone: "accent" }],
      },
      p,
    );
    expect(o.series[0]?.data).toEqual([
      { value: 0, itemStyle: { opacity: 0.45 } },
      { value: 0, itemStyle: { opacity: 0.45 } },
    ]);
  });

  test("the hover anchor sits on the stack top", () => {
    expect([0, 1, 2].map((i) => topAt(calls, i))).toEqual([3, 0, 6]);
  });
});

// The seam that lets the library be swapped (T414.15): nothing outside the adapter imports it.
test("only charts/echarts.ts imports echarts", () => {
  const src = fileURLToPath(new URL("..", import.meta.url));
  const walk = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
      e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)],
    );
  const importers = walk(src)
    .filter((f) => /\.(ts|tsx)$/.test(f) && !f.endsWith(".test.ts"))
    .filter((f) => /from "echarts/.test(readFileSync(f, "utf8")))
    .map((f) => relative(src, f));
  expect(importers).toEqual(["charts/echarts.ts"]);
});
