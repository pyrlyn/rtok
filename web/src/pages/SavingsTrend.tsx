// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useMemo } from "react";
import type { SavingsDay } from "../api/snapshot.gen";
import { Chart, swatch } from "../charts/Chart";
import type { ChartSpec, Series, Tone } from "../charts/spec";
import { Empty } from "../states";
import { Panel } from "../ui/Panel";
import { compact } from "./format";

// Four tones cannot tell a dozen plugins apart, so the biggest savers get their own and the rest
// share one muted segment; the per-plugin numbers stay in the savings-by-plugin table.
const TONES: readonly Tone[] = ["delta", "accent", "accent-soft"];

/** Saved by `plugins` on `d`, or `null` when none of them has a row that day (a gap, not a 0). */
function savedBy(d: SavingsDay, plugins: readonly string[]): number | null {
    const measured = plugins.filter((p) => p in d.plugins);
    return measured.length ? measured.reduce((s, p) => s + (d.plugins[p] ?? 0), 0) : null;
}

/** The trend as a chart, or `null` when no day has a Measurement row: no row, no claim. */
export function savingsSpec(days: readonly SavingsDay[]): ChartSpec | null {
    if (!days.some((d) => d.saved != null)) return null;
    const totals = new Map<string, number>();
    for (const d of days)
        for (const [p, s] of Object.entries(d.plugins)) totals.set(p, (totals.get(p) ?? 0) + s);
    const ranked = [...totals].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    const top = ranked.slice(0, TONES.length).map(([p]) => p);
    const rest = ranked.slice(TONES.length).map(([p]) => p);
    const series: Series[] = top.map((p, k) => ({
        id: p,
        label: p,
        tone: TONES[k] ?? "muted",
        values: days.map((d) => savedBy(d, [p])),
    }));
    if (rest.length)
        series.push({
            // No plugin id holds a `*`, so this cannot collide with a plugin named `other`.
            id: "*other",
            label: `${rest.length} other`,
            tone: "muted",
            values: days.map((d) => savedBy(d, rest)),
        });
    const saved = days.reduce((s, d) => s + (d.saved ?? 0), 0);
    const measured = days.filter((d) => d.saved != null).length;
    return {
        kind: "stacked-bars",
        axes: true,
        label: `Δtok saved per day: ${saved} tokens over the ${measured} of ${days.length} days with Measurement rows`,
        x: days.map((d) => d.day.slice(5)),
        titles: days.map((d) => `${d.day} · ${d.rows} rows`),
        series,
    };
}

/** The Δtok trend panel Overview and Stats share (T414.13). */
export function SavingsTrend({
    days,
    className,
}: {
    days: readonly SavingsDay[];
    className?: string;
}) {
    const spec = useMemo(() => savingsSpec(days), [days]);
    return (
        <Panel
            title="Δtok saved per day"
            hint={`Σ est before − after per day, last ${days.length} days`}
            className={className}
        >
            {spec ? (
                <>
                    <div className="-m-1 overflow-x-auto p-1">
                        <Chart spec={spec} format={compact} className="h-40 w-full min-w-[360px]" />
                    </div>
                    <ul className="flex flex-wrap items-center gap-x-4 gap-y-1 text-2xs text-fg-muted">
                        {spec.series.map((s) => (
                            <li key={s.id} className="flex items-center gap-1.5">
                                <span
                                    aria-hidden="true"
                                    className={`size-2.5 rounded-sm ${swatch[s.tone]}`}
                                />
                                {s.label}{" "}
                                {compact(s.values.reduce<number>((a, v) => a + (v ?? 0), 0))}
                            </li>
                        ))}
                        <li className="ml-auto">empty day = no Measurement rows</li>
                    </ul>
                </>
            ) : (
                <Empty
                    title="No measured savings in these days"
                    hint="A bar appears once a plugin records a Measurement row."
                />
            )}
        </Panel>
    );
}
