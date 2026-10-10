// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useCallback, useEffect, useRef, useState } from "react";
import { prefersReducedMotion } from "../webgl";
import { type Box, EASE_MS, easeOut, lerpBox } from "./camera";

/**
 * Follows `target`, easing only when `key` changes (the camera was sent somewhere else). A
 * `target` that drifts under the same key, as it does while the layout settles, is followed
 * at once: easing every layout frame would leave the picture always one step behind.
 */
export function useEasedBox(target: Box, key: string, enabled: boolean): Box {
  const [shown, setShown] = useState(target);
  const drawn = useRef(target);
  const goal = useRef(target);
  const last = useRef(key);
  const move = useRef<{ from: Box; t0: number } | null>(null);
  const raf = useRef(0);

  const commit = useCallback((b: Box) => {
    drawn.current = b;
    setShown(b);
  }, []);
  const step = useCallback(() => {
    const m = move.current;
    if (!m) return;
    const t = Math.min(1, (performance.now() - m.t0) / EASE_MS);
    commit(t >= 1 ? goal.current : lerpBox(m.from, goal.current, easeOut(t)));
    if (t >= 1) move.current = null;
    else raf.current = requestAnimationFrame(step);
  }, [commit]);

  useEffect(() => {
    // The interactive picture is the target itself; only the read-only one is eased.
    if (!enabled) return;
    goal.current = target;
    // Reduced motion is read per move: the preference can change while the page is open.
    if (prefersReducedMotion()) {
      cancelAnimationFrame(raf.current);
      move.current = null;
      last.current = key;
      commit(target);
    } else if (key !== last.current) {
      last.current = key;
      cancelAnimationFrame(raf.current);
      move.current = { from: drawn.current, t0: performance.now() };
      raf.current = requestAnimationFrame(step);
    } else if (!move.current) commit(target);
  }, [target.x, target.y, target.w, target.h, key, enabled, commit, step]);
  useEffect(() => () => cancelAnimationFrame(raf.current), []);

  return enabled ? shown : target;
}
