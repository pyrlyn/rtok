// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ChartSpec } from "./spec";

/** A point in viewport coordinates the tooltip hangs from. */
export interface Anchor {
  x: number;
  y: number;
}

export interface RendererEvents {
  /** The pointer is over x index `index`. */
  hover(index: number): void;
  leave(): void;
}

export interface ChartView {
  update(spec: ChartSpec): void;
  /** Show the hover marker at `index` without emitting `hover` (keyboard, sync). */
  pointer(index: number | null): void;
  anchor(index: number): Anchor | null;
  dispose(): void;
}

/** The one seam a chart library plugs into; `echarts.ts` is today's only implementation. */
export type Renderer = (el: HTMLElement, spec: ChartSpec, events: RendererEvents) => ChartView;

// Loaded on first use so the library stays out of the entry chunk.
let pending: Promise<Renderer> | undefined;
export const loadRenderer = () => (pending ??= import("./echarts").then((m) => m.echartsRenderer));
