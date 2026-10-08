// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useMemo, type ReactNode } from "react";
import { HoverScope, useHover, useScope } from "./hover";

/** What a card shows live for the bucket another chart of its sync group is hovering. */
export interface Readout {
    group: string;
    x: readonly string[];
    values: readonly number[];
    unit: string;
}

/** One scope per card, so a chart and the card's own readout can tell they belong together. */
export function Scoped({ children }: { children: ReactNode }) {
    const id = useMemo(() => Symbol("card"), []);
    return <HoverScope value={id}>{children}</HoverScope>;
}

/**
 * The card's subline, swapped for "time · value" while another chart's hover sits on a bucket.
 * The tooltip of the hovered chart is the accessible reading, so the swap is hidden from assistive
 * tech and the original subline stays in the tree (visually hidden) instead of being lost.
 */
export function HoverSub({ readout, children }: { readout?: Readout; children?: ReactNode }) {
    const hover = useHover(readout?.group);
    const scope = useScope();
    const at =
        readout && hover && hover.owner !== scope && hover.index < readout.x.length
            ? hover.index
            : null;
    if (!readout || at == null) return children;
    return (
        <>
            <span className="sr-only">{children}</span>
            <span aria-hidden="true" data-testid="hover-readout" className="tabular-nums">
                {readout.x[at]} · {(readout.values[at] ?? 0).toLocaleString()} {readout.unit}
            </span>
        </>
    );
}
