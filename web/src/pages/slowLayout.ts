// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Frame, LayoutInput } from "./graph3d/layout";

/**
 * A story `beforeEach`: a node that a later topology adds to the layout gets its position `ms`
 * after the frame that first places it, while the nodes already there keep moving. A busy CI host
 * does this at random; here it happens on every run however fast the worker starts, so a play
 * function that queries such a node without waiting fails on any machine. Returns the cleanup.
 */
export const slowNewNodes = (ms: number) => () => {
  const Real = globalThis.Worker;
  globalThis.Worker = class extends Real {
    constructor(url: string | URL, options?: WorkerOptions) {
      super(url, options);
      let handler: Worker["onmessage"] = null;
      const known = new Set<number>();
      // A late id maps to the time its position may show, once a frame first carries it.
      const late = new Map<number, number | undefined>();
      let latest: Frame | undefined;
      // The page sets `onmessage`; keeping it here routes every frame through `pass`.
      Object.defineProperty(this, "onmessage", {
        get: () => handler,
        set: (fn: Worker["onmessage"]) => void (handler = fn),
      });
      const pass = (f: Frame) => {
        const now = performance.now();
        for (const id of f.ids) {
          if (!late.has(id) || late.get(id) !== undefined) continue;
          late.set(id, now + ms);
          // A layout that settles meanwhile sends nothing more, so the held node is shown by a resend.
          setTimeout(() => latest && pass(latest), ms);
        }
        const shown = f.ids.flatMap((id, i) => ((late.get(id) ?? 0) > now ? [] : [i]));
        const data: Frame = {
          ids: shown.map((i) => f.ids[i]!),
          pos: Float32Array.from(shown.flatMap((i) => [...f.pos.subarray(i * 3, i * 3 + 3)])),
          settled: f.settled,
        };
        handler?.call(this, new MessageEvent("message", { data }));
      };
      this.addEventListener("message", (e: MessageEvent<Frame>) => pass((latest = e.data)));
      const post = this.postMessage.bind(this) as (m: unknown) => void;
      this.postMessage = ((input: LayoutInput) => {
        for (const { id } of input.nodes) {
          if (known.size > 0 && !known.has(id)) late.set(id, undefined);
        }
        for (const { id } of input.nodes) known.add(id);
        post(input);
      }) as Worker["postMessage"];
    }
  };
  return () => {
    globalThis.Worker = Real;
  };
};
