// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The only file that imports ECharts (T414.15). Everything it knows comes from a ChartSpec, and
// everything it reports goes out through RendererEvents, so another library replaces this file
// alone.

import { BarChart, LineChart, ScatterChart } from "echarts/charts";
import { AxisPointerComponent, GridComponent, TooltipComponent } from "echarts/components";
import * as echarts from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import type { Renderer } from "./renderer";
import { seriesAt, stackAt, type ChartSpec, type Tone } from "./spec";

echarts.use([
  BarChart,
  LineChart,
  ScatterChart,
  GridComponent,
  TooltipComponent,
  AxisPointerComponent,
  CanvasRenderer,
]);

export interface Palette {
  accent: string;
  muted: string;
  subtle: string;
  delta: string;
  border: string;
  font: string;
}

// Canvas cannot resolve CSS variables, so the roles are read on every draw (a theme switch
// redraws, see the MutationObserver below).
export function palette(root: Element = document.documentElement): Palette {
  const s = getComputedStyle(root);
  const v = (name: string) => s.getPropertyValue(name).trim();
  return {
    accent: v("--pyr-accent-fg"),
    muted: v("--pyr-fg-muted"),
    subtle: v("--pyr-fg-subtle"),
    delta: v("--pyr-delta-fg"),
    border: v("--pyr-border"),
    font: v("--pyr-font-mono"),
  };
}

const toneStyle = (p: Palette, t: Tone) =>
  ({
    accent: { color: p.accent, opacity: 1 },
    "accent-soft": { color: p.accent, opacity: 0.5 },
    muted: { color: p.muted, opacity: 0.75 },
    delta: { color: p.delta, opacity: 1 },
  })[t];

/** The value the hover marker sits on at `i`. */
export const topAt = (spec: ChartSpec, i: number) =>
  spec.kind === "stacked-bars"
    ? stackAt(spec, i)
    : Math.max(...spec.series.map((s) => s.values[i] ?? 0));

export function toOption(spec: ChartSpec, p: Palette) {
  const axes = !!spec.axes;
  const line = spec.kind === "line";
  const peak = Math.max(1, ...spec.x.map((_, i) => topAt(spec, i)));
  // Four even ticks with integer steps, as the SVG chart had.
  const top = Math.ceil(peak / 4) * 4;
  const text = { color: p.muted, fontSize: 9, fontFamily: p.font };
  return {
    animation: false,
    textStyle: { fontFamily: p.font },
    grid: axes
      ? { left: 32, right: 8, top: 8, bottom: 22 }
      : { left: 0, right: 0, top: 2, bottom: 1 },
    // The tooltip component drives hit-testing and the axis pointer; its own box stays off
    // because the tooltip is ours (Tooltip.tsx).
    tooltip: {
      trigger: "axis",
      showContent: false,
      axisPointer: {
        // Minis are too small for a shaded column; a hairline reads better there.
        type: line || !axes ? "line" : "shadow",
        lineStyle: { color: p.subtle, width: 1 },
        shadowStyle: { color: p.accent, opacity: 0.12 },
      },
    },
    xAxis: {
      type: "category",
      data: [...spec.x],
      show: axes,
      boundaryGap: !line,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: p.border } },
      axisLabel: { ...text, interval: 3 },
    },
    yAxis: {
      type: "value",
      show: axes,
      min: line && !axes ? "dataMin" : 0,
      max: axes ? top : line ? "dataMax" : peak,
      interval: axes ? top / 4 : undefined,
      splitLine: { show: axes, lineStyle: { color: p.border, type: [2, 3] } },
      axisLabel: text,
    },
    series: [
      ...spec.series.map((s) => {
        const { color, opacity } = toneStyle(p, s.tone);
        const base = { id: s.id, name: s.label, emphasis: { disabled: true } };
        if (line)
          return {
            ...base,
            type: "line",
            data: [...s.values],
            symbol: "none",
            lineStyle: { color, width: 1.5 },
            areaStyle: { color, opacity: 0.12 },
          };
        return {
          ...base,
          type: "bar",
          stack: spec.kind === "stacked-bars" ? "all" : undefined,
          barCategoryGap: axes ? "12%" : "25%",
          itemStyle: { color, opacity, borderRadius: axes ? 0 : 1 },
          // Minis fade low bars so the shape reads at 28px, as the SVG minis did.
          data: s.values.map((v) =>
            axes ? v : { value: v, itemStyle: { opacity: 0.45 + 0.55 * (v / peak) } },
          ),
        };
      }),
      ...(spec.dots
        ? [
            {
              id: spec.dots.id,
              name: spec.dots.label,
              type: "scatter",
              symbolSize: 5,
              symbolOffset: [0, -6],
              itemStyle: toneStyle(p, spec.dots.tone),
              emphasis: { disabled: true },
              data: spec.dots.values.map((v, i) => (v > 0 ? topAt(spec, i) : null)),
            },
          ]
        : []),
    ],
  };
}

export const echartsRenderer: Renderer = (el, first, events) => {
  const chart = echarts.init(el, undefined, { renderer: "canvas" });
  let spec = first;
  // Set while we move the pointer ourselves, so a programmatic move is not echoed back as hover.
  let quiet = false;
  const draw = () => chart.setOption(toOption(spec, palette()), { replaceMerge: ["series"] });
  draw();

  chart.on("updateAxisPointer", (e) => {
    const i = (e as { axesInfo?: { value?: unknown }[] }).axesInfo?.[0]?.value;
    if (!quiet && typeof i === "number") events.hover(i);
  });
  let onSeries: string | null = null;
  chart.getZr().on("mousemove", (e) => {
    if (!events.series || !spec.axes) return;
    // Hit-testing by value, not by ECharts' own series events, which stay quiet with emphasis off.
    const at = chart.convertFromPixel({ gridIndex: 0 }, [e.offsetX, e.offsetY]);
    const id = at ? seriesAt(spec, Math.round(at[0] ?? NaN), at[1] ?? NaN) : null;
    if (id !== onSeries) events.series((onSeries = id));
  });
  chart.getZr().on("globalout", () => {
    onSeries = null;
    events.leave();
  });

  const resize = new ResizeObserver(() => chart.resize());
  resize.observe(el);
  const theme = new MutationObserver(draw);
  theme.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });

  return {
    update(next) {
      spec = next;
      draw();
    },
    pointer(i) {
      quiet = true;
      // As a pointer would, by pixel: `showTip` by index skips the shaded column of a bar chart.
      // Mid-height is inside the grid for both the full chart and the 28px minis.
      const x = i == null ? undefined : chart.convertToPixel({ gridIndex: 0 }, [i, 0])?.[0];
      chart.dispatchAction(
        x == null
          ? { type: "updateAxisPointer", currTrigger: "leave" }
          : { type: "updateAxisPointer", currTrigger: "mousemove", x, y: el.clientHeight / 2 },
      );
      quiet = false;
    },
    anchor(i) {
      const px = chart.convertToPixel({ gridIndex: 0 }, [i, topAt(spec, i)]);
      const [x, y] = px ?? [];
      if (x == null || y == null) return null;
      const r = el.getBoundingClientRect();
      return { x: r.left + x, y: r.top + y };
    },
    dispose() {
      resize.disconnect();
      theme.disconnect();
      chart.dispose();
    },
  };
};
