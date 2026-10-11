// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useEffect, useRef, useState } from "react";
import { fmt } from "../../format";
import { prefersReducedMotion } from "../webgl";

/** How long a live number takes to reach its new value. */
export const COUNT_MS = 400;

const easeOut = (t: number) => t * (2 - t);

/**
 * `value` over a short count-up; `prefers-reduced-motion` jumps (T329.53). The drawn number
 * eases from wherever it is, so a value that moves mid-count is followed, not restarted.
 */
export function useCountUp(value: number): number {
    const [shown, setShown] = useState(value);
    const drawn = useRef(value);
    const goal = useRef(value);
    const move = useRef<{ from: number; t0: number } | null>(null);
    const raf = useRef(0);

    useEffect(() => {
        goal.current = value;
        if (prefersReducedMotion()) {
            cancelAnimationFrame(raf.current);
            move.current = null;
            drawn.current = value;
            setShown(value);
            return;
        }
        if (drawn.current === value && !move.current) return;
        // Restart the ease from where the number is: a moving target is followed, not jumped.
        move.current = { from: drawn.current, t0: performance.now() };
        const step = () => {
            const m = move.current;
            if (!m) return;
            const t = Math.min(1, (performance.now() - m.t0) / COUNT_MS);
            const eased = m.from + (goal.current - m.from) * easeOut(t);
            // A count of things passes through whole numbers; "15.169809" is never a count.
            const next =
                t >= 1 ? goal.current : Number.isInteger(goal.current) ? Math.round(eased) : eased;
            drawn.current = next;
            setShown(next);
            if (t >= 1) move.current = null;
            else raf.current = requestAnimationFrame(step);
        };
        cancelAnimationFrame(raf.current);
        raf.current = requestAnimationFrame(step);
    }, [value]);
    useEffect(() => () => cancelAnimationFrame(raf.current), []);

    return shown;
}

/**
 * The animated number. While it is still counting, the drawn frame is a picture (`aria-hidden`)
 * and the server's total is the only text assistive tech reads; once it lands there is a single
 * node, so the total is not duplicated (T329.53).
 */
export function CountUp({
    value,
    format = fmt,
}: {
    value: number;
    format?: (n: number) => string;
}) {
    const shown = useCountUp(value);
    if (shown === value) return format(value);
    return (
        <>
            <span aria-hidden="true">{format(shown)}</span>
            <span className="sr-only">{format(value)}</span>
        </>
    );
}
