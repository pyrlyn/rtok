// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// What a page says about a chart. Nothing here names a chart library, so swapping the renderer
// (renderer.ts) never touches a page (T414.15).

/** Brand roles a series may take; the renderer resolves them from the `--pyr-*` tokens. */
export type Tone = "accent" | "accent-soft" | "muted" | "delta";

export interface Series {
  id: string;
  label: string;
  values: readonly number[];
  tone: Tone;
}

export interface ChartSpec {
  kind: "line" | "bars" | "stacked-bars";
  /** One tick label per x index. */
  x: readonly string[];
  /** Tooltip heading per x index when ticks repeat (minute ticks over sub-minute buckets). */
  titles?: readonly string[];
  series: readonly Series[];
  /** Accessible name of the whole chart. */
  label: string;
  /** Axes, grid and tick labels; minis leave them off. */
  axes?: boolean;
  /** Marks drawn above the top of each column where the value is non-zero (failed calls). */
  dots?: Series;
  /** Charts that share a group move one hover together. */
  sync?: string;
}

/** Sum of every series at `i`: the top of a stacked column. */
export const stackAt = (spec: ChartSpec, i: number) =>
  spec.series.reduce((s, x) => s + (x.values[i] ?? 0), 0);

/**
 * The series whose segment of the stacked column at `i` holds `value` (a y reading of the pointer),
 * or null above the column, on a zero segment or on any other chart kind. The calls legend lights
 * the series the pointer is on with it (T414.16).
 */
export function seriesAt(spec: ChartSpec, i: number, value: number): string | null {
  if (spec.kind !== "stacked-bars") return null;
  let lo = 0;
  for (const s of spec.series) {
    const hi = lo + (s.values[i] ?? 0);
    if (hi > lo && value > lo && value <= hi) return s.id;
    lo = hi;
  }
  return null;
}
