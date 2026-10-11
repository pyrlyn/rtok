// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useId, useMemo, useState } from "react";
import type { CallsView, WindowView } from "../../../api/snapshot.gen";
import { Chip } from "../../../ui/Chip";
import { Kpi } from "../../../ui/Kpi";
import { Pill } from "../../../ui/Pill";
import { Sparkline } from "../../../ui/Sparkline";
import { compact, fmt, millis, pct, seconds } from "../../format";
import { useMinWidth } from "../../parts";
import { CountUp } from "./CountUp";
import { MetricsStrip } from "./MetricsStrip";
import { emptyWindow } from "./useCalls";

/** The width where part 2 sits beside part 1 (`Split`); below it the metrics fold to a strip. */
export const STRIP_BELOW_PX = 900;

const minutes = (ms: number) => `${Math.round(ms / 60_000)} min`;

/** A sparkline of the server's slots (T329.32); the TUI draws the same series. */
function Trend({
    spark,
    values,
    what,
}: {
    spark: WindowView["spark"];
    values: number[];
    what: string;
}) {
    const slot = spark.span_ms / Math.max(1, values.length);
    const x = useMemo(
        () => values.map((_, i) => `${seconds((values.length - i) * slot)} ago`),
        [values, slot],
    );
    return (
        <Sparkline
            values={values}
            x={x}
            label={`${what} over the last ${minutes(spark.span_ms)}`}
            name={what}
        />
    );
}

/**
 * The server computes every number (T484), from `summary` and the `Measurement` samples of the end
 * events, which are the rows `rtok stats` adds up, so a window here equals `rtok stats` over the
 * same calls, and `rtok tui` shows the same figures. Latency is the exception: a percentile cannot
 * be summed, so it is measured on the calls a batch lists.
 */
export function LiveMetrics(props: Props) {
    const { view, window, frozen } = props;
    const wide = useMinWidth(STRIP_BELOW_PX);
    // The choice outlives the window chips and the freeze, which re-render this.
    const [open, setOpen] = useState(false);
    const body = useId();
    const t = view.windows[window] ?? emptyWindow;
    return (
        <div className="flex flex-col gap-2">
            {!wide && (
                <MetricsStrip
                    running={<CountUp value={view.running.length} />}
                    calls={<CountUp value={t.calls} />}
                    saved={<CountUp value={t.before - t.after} format={compact} />}
                    window={t.label}
                    frozen={frozen}
                    open={open}
                    onToggle={() => setOpen(!open)}
                    controls={body}
                />
            )}
            {(wide || open) && <MetricsCards id={body} {...props} />}
        </div>
    );
}

interface Props {
    view: CallsView;
    now: number;
    /** Index into the server's windows. */
    window: number;
    onWindow(next: number): void;
    frozen: boolean;
    /** Calls that arrived while the picture was held. */
    pending: number;
    onFreeze(on: boolean): void;
}

function MetricsCards({
    id,
    view,
    now,
    window,
    onWindow,
    frozen,
    pending,
    onFreeze,
}: Props & { id: string }) {
    const t = view.windows[window] ?? emptyWindow;
    const saved = t.before - t.after;
    const lat = t.latency;
    const top = Math.max(1, ...t.tools.map((v) => v.calls));
    const listed = Object.values(t.backends).reduce((n, v) => n + v, 0);
    return (
        <div id={id} className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
                <div role="group" aria-label="window" className="flex gap-1">
                    {view.windows.map((w, i) => (
                        <Chip
                            key={w.label}
                            pressed={window === i}
                            onPressedChange={() => onWindow(i)}
                        >
                            {w.label}
                        </Chip>
                    ))}
                </div>
                <div className="ml-auto flex items-center gap-2">
                    {frozen && pending > 0 && (
                        <span role="status" className="text-2xs text-fg-muted">
                            {fmt(pending)} held
                        </span>
                    )}
                    <Chip pressed={frozen} onPressedChange={onFreeze}>
                        {frozen ? "Unfreeze" : "Freeze"}
                    </Chip>
                </div>
            </div>
            <div className="grid grid-cols-2 gap-2 xl:grid-cols-3">
                <Kpi
                    label="running"
                    value={<CountUp value={view.running.length} />}
                    sub={view.running.length ? view.running.map((r) => r.tool).join(", ") : "idle"}
                />
                <Kpi
                    label="calls"
                    value={<CountUp value={t.calls} />}
                    tone={t.failed ? "warn" : "default"}
                    sub={`${fmt(t.failed)} failed`}
                    viz={<Trend spark={t.spark} values={t.spark.calls} what="calls" />}
                />
                <Kpi
                    label="latency p50"
                    value={lat ? <CountUp value={lat.p50} format={millis} /> : "-"}
                    sub={lat ? `p95 ${millis(lat.p95)}` : undefined}
                />
                <Kpi
                    label="symbols returned"
                    value={
                        <>
                            <CountUp value={t.symbols_returned} /> of <CountUp value={t.symbols} />
                        </>
                    }
                    sub={`${fmt(t.crossed)} across projects`}
                />
                <Kpi label="files touched" value={<CountUp value={t.files_touched} />} />
                <Kpi label="projects with hits" value={<CountUp value={t.projects_hit} />} />
                <Kpi
                    label="fallbacks"
                    value={<CountUp value={t.fallbacks} />}
                    tone={t.fallbacks ? "warn" : "default"}
                    sub={`${fmt(t.caps)} capped`}
                />
                <Kpi label="tokens sent" value={<CountUp value={t.after} format={compact} />} />
                <Kpi label="without rtok" value={<CountUp value={t.before} format={compact} />} />
                <Kpi
                    label="saved"
                    value={<CountUp value={saved} format={compact} />}
                    tone="saved"
                    sub={t.before ? pct(saved / t.before, 0) : "-"}
                    viz={<Trend spark={t.spark} values={t.spark.saved} what="tokens saved" />}
                />
            </div>
            {view.running.length > 0 && (
                <ul aria-label="running calls" className="flex flex-col gap-1 text-xs">
                    {view.running.map((r) => (
                        <li key={r.call} className="flex flex-wrap items-center gap-2">
                            <b>{r.tool}</b>
                            <span className="truncate text-fg-muted">{r.target ?? "-"}</span>
                            <span className="text-fg-subtle">{r.project ?? "-"}</span>
                            <span className="ml-auto text-fg-subtle">{seconds(now - r.at)}</span>
                        </li>
                    ))}
                </ul>
            )}
            <ul aria-label="calls per tool" className="flex flex-col gap-1 text-xs">
                {t.tools.map((v) => (
                    <li key={v.tool} className="grid grid-cols-[6rem_1fr_auto] items-center gap-2">
                        <span>{v.tool}</span>
                        <span
                            role="presentation"
                            style={{ width: `${(v.calls / top) * 100}%` }}
                            className="h-2 rounded-full bg-accent/60"
                        />
                        <span className="text-fg-subtle">
                            {fmt(v.calls)} · {compact(v.saved)} saved
                        </span>
                    </li>
                ))}
            </ul>
            {listed > 0 && (
                <p className="flex flex-wrap items-center gap-1.5 text-2xs text-fg-subtle">
                    backend
                    {Object.entries(t.backends).map(([b, n]) => (
                        <Pill key={b}>
                            {b} {pct(n / listed, 0)}
                        </Pill>
                    ))}
                </p>
            )}
        </div>
    );
}
