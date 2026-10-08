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
  /** `null` is no data at that index: a gap, and the tooltip says so instead of showing 0. */
  values: readonly (number | null)[];
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
