// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

/** Pixels the stage drew: the canvas is transparent where nothing is. */
export function drawn(canvas: HTMLCanvasElement): number {
  const copy = document.createElement("canvas");
  copy.width = canvas.width;
  copy.height = canvas.height;
  const ctx = copy.getContext("2d")!;
  ctx.drawImage(canvas, 0, 0);
  const px = ctx.getImageData(0, 0, copy.width, copy.height).data;
  let n = 0;
  for (let i = 3; i < px.length; i += 4) if (px[i]! > 0) n++;
  return n;
}
