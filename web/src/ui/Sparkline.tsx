// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useMemo } from "react";
import { Chart } from "../charts/Chart";
import type { ChartSpec } from "../charts/spec";

export interface MiniProps {
    values: readonly number[];
    /** Accessible name of the whole chart. */
    label: string;
    /** The series name in the tooltip; defaults to `label`. */
    name?: string;
    /** Tooltip heading per point; defaults to the point's position. */
    x?: readonly string[];
    sync?: string;
    format?: (v: number) => string;
}

/** A 28px chart for KPI cards: a line (`Sparkline`) or bars (`MiniBars`). */
export function Mini({
    kind,
    values,
    label,
    name,
    x,
    sync,
    format,
}: MiniProps & { kind: ChartSpec["kind"] }) {
    const spec = useMemo<ChartSpec>(
        () => ({
            kind,
            label,
            sync,
            x: x ?? values.map((_, i) => `#${i + 1}`),
            series: [{ id: "v", label: name ?? label, values, tone: "accent" }],
        }),
        [kind, label, sync, x, values, name],
    );
    if (values.length < (kind === "line" ? 2 : 1))
        return <div aria-hidden="true" className="h-7" />;
    return <Chart spec={spec} format={format} className="h-7 w-full" />;
}

export const Sparkline = (p: MiniProps) => <Mini kind="line" {...p} />;
