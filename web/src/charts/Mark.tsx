// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useId, useRef, useState, type HTMLAttributes, type ReactNode } from "react";
import type { Anchor } from "./renderer";
import { Tooltip } from "./Tooltip";

/**
 * A small DOM mark (a dot, a bar segment, a grid) that shows the chart tooltip on hover. The
 * tooltip is a convenience: the figure it states must also sit in the mark's own label or in
 * visible text next to it, because a hover is no way to read data.
 */
export function Mark({
    tip,
    children,
    ...rest
}: { tip: ReactNode } & Omit<HTMLAttributes<HTMLDivElement>, "title">) {
    const id = useId();
    const ref = useRef<HTMLDivElement>(null);
    const [anchor, setAnchor] = useState<Anchor | null>(null);
    const show = () => {
        const r = ref.current?.getBoundingClientRect();
        if (r) setAnchor({ x: r.left + r.width / 2, y: r.top });
    };
    return (
        <>
            <div
                {...rest}
                ref={ref}
                aria-describedby={anchor ? id : undefined}
                onPointerEnter={show}
                onPointerLeave={() => setAnchor(null)}
            >
                {children}
            </div>
            {anchor && (
                <Tooltip id={id} anchor={anchor}>
                    {tip}
                </Tooltip>
            )}
        </>
    );
}

/** Heading and rows in the look the chart tooltip uses. */
export function MarkRows({ title, rows }: { title: string; rows: readonly [string, string][] }) {
    return (
        <>
            <p className="mb-1 font-semibold text-fg">{title}</p>
            <ul className="flex flex-col gap-0.5">
                {rows.map(([k, v]) => (
                    <li key={k} className="flex gap-3">
                        {k}
                        <span className="ml-auto text-fg tabular-nums">{v}</span>
                    </li>
                ))}
            </ul>
        </>
    );
}
