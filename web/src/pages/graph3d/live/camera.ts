// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Scene } from "../scene";
import type { Vec3 } from "../useLayout";

/** How long the camera takes to move between the overview and the running call. */
export const EASE_MS = 450;

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

const PAD = 30;
/** A framed node alone must not fill the view: the picture around it stays readable. */
const MIN_SPAN = 140;

export const easeOut = (t: number) => t * (2 - t);

export function lerpBox(a: Box, b: Box, k: number): Box {
  const at = (from: number, to: number) => from + (to - from) * k;
  return { x: at(a.x, b.x), y: at(a.y, b.y), w: at(a.w, b.w), h: at(a.h, b.h) };
}

/** The view box around `ids` (every node when none), padded; `ids` that have no position are left out. */
export function boxAround(scene: Scene, map: Map<number, Vec3>, ids?: readonly number[]): Box {
  const wanted = ids?.length ? new Set(ids) : null;
  const pts = scene.nodes.flatMap((n) => {
    const p = map.get(n.id);
    return p && (!wanted || wanted.has(n.id)) ? [{ p, r: n.radius }] : [];
  });
  if (!pts.length) return wanted ? boxAround(scene, map) : { x: -100, y: -100, w: 200, h: 200 };
  const x0 = Math.min(...pts.map(({ p, r }) => p[0] - r));
  const x1 = Math.max(...pts.map(({ p, r }) => p[0] + r));
  const y0 = Math.min(...pts.map(({ p, r }) => p[1] - r));
  const y1 = Math.max(...pts.map(({ p, r }) => p[1] + r));
  const box = { x: x0 - PAD, y: y0 - PAD, w: x1 - x0 + 2 * PAD, h: y1 - y0 + 2 * PAD };
  if (!wanted) return box;
  const w = Math.max(box.w, MIN_SPAN);
  const h = Math.max(box.h, MIN_SPAN);
  return { x: box.x - (w - box.w) / 2, y: box.y - (h - box.h) / 2, w, h };
}

/** The smallest sphere (around the centroid) holding every node's surface; `floor` keeps a lone node from zooming in to a point. */
export function sphereOf(
  pts: { p: Vec3; r: number }[],
  floor: number,
): { center: Vec3; radius: number } | null {
  if (!pts.length) return null;
  const sum = pts.reduce<Vec3>((s, { p }) => [s[0] + p[0], s[1] + p[1], s[2] + p[2]], [0, 0, 0]);
  const center: Vec3 = [sum[0] / pts.length, sum[1] / pts.length, sum[2] / pts.length];
  const radius = Math.max(
    floor,
    ...pts.map(({ p, r }) => Math.hypot(p[0] - center[0], p[1] - center[1], p[2] - center[2]) + r),
  );
  return { center, radius };
}
