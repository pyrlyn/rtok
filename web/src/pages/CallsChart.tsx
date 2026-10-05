// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useMemo } from "react";
import { Chart } from "../charts/Chart";
import type { ChartSpec, Tone } from "../charts/spec";
import { hm, hms } from "./format";
import { SURFACES, type Bucket, type Surface } from "./model";

export const SERIES: Record<Surface, Tone> = { hook: "accent", mcp: "accent-soft", proxy: "muted" };

/** Charts on the calls window share this sync group (T414.15). */
export const CALLS_SYNC = "calls";

export function CallsChart({
    buckets,
    step,
    failed,
}: {
    buckets: readonly Bucket[];
    step: number;
    failed: number;
}) {
    const spec = useMemo<ChartSpec>(() => {
        const all = buckets.reduce((s, b) => s + b.hook + b.mcp + b.proxy, 0);
        return {
            kind: "stacked-bars",
            axes: true,
            sync: CALLS_SYNC,
            label: `Calls over time: ${all} calls in ${buckets.length} buckets of ${Math.max(1, Math.round(step / 60))} minutes, ${failed} failed`,
            x: buckets.map((b) => hm(b.t)),
            titles: buckets.map((b) => `${hms(b.t)} – ${hms(b.t + step)}`),
            series: SURFACES.map((s) => ({
                id: s,
                label: s,
                values: buckets.map((b) => b[s]),
                tone: SERIES[s],
            })),
            dots: {
                id: "failed",
                label: "failed",
                values: buckets.map((b) => b.err),
                tone: "delta",
            },
        };
    }, [buckets, step, failed]);
    // The 9px axis text is unreadable once the chart shrinks to a phone, so it scrolls instead; the
    // padding keeps the focus ring inside the scroll box, which would clip it.
    return (
        <div className="-m-1 overflow-x-auto p-1">
            <Chart spec={spec} className="h-44 w-full min-w-[520px]" />
        </div>
    );
}
