// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useMemo, type ReactNode } from "react";
import { Empty } from "../states";
import { DataTable, type Column } from "../ui/DataTable";
import { ExportButtons } from "../ui/ExportButtons";

import { Mark, MarkRows } from "../charts/Mark";

import { Kpi } from "../ui/Kpi";
import { Panel } from "../ui/Panel";
import { Pill } from "../ui/Pill";
import { Bitset, BudgetGrid, MiniBars } from "../ui/Marks";
import { Sparkline } from "../ui/Sparkline";
import { CALLS_SYNC } from "./CallsChart";
import { compact, fmt, hms, pct } from "./format";
import { overview } from "./model";
import { CallsPanel, DoctorPanel, SessionsPanel } from "./OverviewPanels";
import { PanelLink, TokenMix, tokenTotal, WithSnapshot } from "./parts";
import { tableLink } from "../tableSearch";
import { SavingsTrend } from "./SavingsTrend";

type Saving = ReturnType<typeof overview>["measured"][number];

function savingColumns(max: number): Column<Saving>[] {
    return [
        {
            id: "plugin",
            header: "plugin",
            exportValue: (r) => r.plugin.id,
            cell: (r) => <b>{r.plugin.id}</b>,
        },
        {
            id: "rows",
            header: "rows",
            width: "64px",
            align: "right",
            exportValue: (r) => r.plugin.stats?.rows,
            cell: (r) => fmt(r.plugin.stats?.rows),
        },
        {
            id: "saved",
            header: "saved",
            width: "72px",
            align: "right",
            exportValue: (r) => r.saved,
            cell: (r) => compact(r.saved),
        },
        {
            id: "share",
            header: "share",
            cell: (r) => <Share id={r.plugin.id} value={r.saved} max={max} />,
        },
    ];
}

function Share({ id, value, max }: { id: string; value: number; max: number }) {
    return (
        <Mark
            tip={
                <MarkRows
                    title={id}
                    rows={[
                        ["saved", compact(value)],
                        ["of the top plugin", pct(max ? value / max : 0, 0)],
                    ]}
                />
            }
            className="h-1.5 overflow-hidden rounded-full bg-surface-3"
        >
            <div
                className="h-full rounded-full bg-delta-fg/80"
                style={{ width: `${max ? (value / max) * 100 : 0}%` }}
            />
        </Mark>
    );
}

const sub = (text: string) => <span className="text-sm text-fg-subtle">{text}</span>;

const kicker = "text-2xs font-semibold tracking-kicker text-fg-subtle uppercase";

function Budget({
    label,
    value,
    of,
    kept,
}: {
    label: string;
    value: number;
    of: number;
    kept?: boolean;
}) {
    return (
        <div className="grid grid-cols-[5.5rem_minmax(0,1fr)_3rem] items-center gap-2">
            <dt>{label}</dt>
            <dd className="h-2 overflow-hidden rounded-full bg-surface-3">
                <div
                    className={`h-full rounded-full ${kept ? "bg-accent" : "bg-border-strong"}`}
                    style={{ width: `${of ? Math.min(100, (value / of) * 100) : 0}%` }}
                />
            </dd>
            <dd className="text-right text-fg tabular-nums">{compact(value)}</dd>
        </div>
    );
}

// The product's one claim, measured: what rtok cut and what share of the estimate that is.
function Saved({ o }: { o: ReturnType<typeof overview> }) {
    return (
        <section
            aria-label="tokens saved"
            className="glass flex flex-wrap items-center gap-x-8 gap-y-4 p-4"
        >
            <BudgetGrid cut={o.deltaPct} label={`${pct(o.deltaPct)} of the estimated tokens cut`} />
            <div className="min-w-0">
                <p className={kicker}>Δ saved tok</p>
                <p className="mt-1 text-3xl font-semibold whitespace-nowrap">
                    <span className="text-delta-fg">Δ</span> {compact(o.saved)}
                </p>
                <p className="mt-1 text-2xs text-fg-muted">
                    est {compact(o.estBefore)} → {compact(o.estAfter)}
                </p>
            </div>
            <div className="min-w-0">
                <p className={kicker}>Δtok %</p>
                <p className="mt-1 text-3xl font-semibold text-accent-fg">{pct(o.deltaPct)}</p>
                <p className="mt-1 text-2xs text-fg-muted">
                    {o.measured.length} plugins with Measurement rows
                </p>
            </div>
            <dl className="ml-auto flex min-w-48 flex-1 flex-col gap-2 text-2xs text-fg-muted sm:max-w-80">
                <Budget label="est before" value={o.estBefore} of={o.estBefore} />
                <Budget label="after" value={o.estAfter} of={o.estBefore} kept />
            </dl>
        </section>
    );
}

export function Overview() {
    return <WithSnapshot>{(snap) => <OverviewBody snap={snap} />}</WithSnapshot>;
}

function OverviewBody({ snap }: { snap: Parameters<typeof overview>[0] }) {
    const o = overview(snap);
    const u = o.usage;
    const max = Math.max(1, ...o.measured.map((m) => m.saved));
    const columns = useMemo(() => savingColumns(max), [max]);
    const turns = useMemo(() => u.turns.slice(-40), [u.turns]);
    // Minis show no ticks, so their x can carry seconds and tell sub-minute buckets apart.
    const times = useMemo(() => o.buckets.map((b) => hms(b.t)), [o.buckets]);
    const perBucket = useMemo(() => o.buckets.map((b) => b.hook + b.mcp + b.proxy), [o.buckets]);
    const kpis: ReactNode[] = [
        <Kpi
            key="in"
            label="input tok"
            value={compact(u.input)}
            sub={`ctx ${compact(o.ctx)} incl. cache`}
            viz={
                <Sparkline
                    values={turns}
                    label="ctx tokens per turn"
                    name="ctx tok"
                    x={turns.map((_, i) => `turn ${u.turns.length - turns.length + i + 1}`)}
                    format={compact}
                />
            }
        />,
        <Kpi
            key="out"
            label="output tok"
            value={compact(u.output)}
            sub={`cache create ${compact(u.cache_create)}`}
        />,
        <Kpi
            key="cache"
            label="cache hit"
            value={pct(o.cacheHit)}
            sub={`read ${compact(u.cache_read)} of ctx`}
        />,
        <Kpi
            key="calls"
            label="calls"
            value={fmt(snap.calls.length)}
            to={tableLink("calls", {})}
            sub={
                <>
                    {o.failed ? (
                        // Lifted above the card's overlay so it is its own link; underlined because it sits inside a sentence.
                        <PanelLink
                            {...tableLink("calls", { result: "failed" })}
                            className="relative z-10 underline"
                        >
                            {o.failed} failed
                        </PanelLink>
                    ) : (
                        "0 failed"
                    )}
                    {` · p95 ${o.p95 == null ? "-" : `${o.p95.toFixed(0)} ms`}`}
                </>
            }
            readout={{ group: CALLS_SYNC, x: times, values: perBucket, unit: "calls" }}
            viz={
                <MiniBars
                    values={perBucket}
                    label="calls per bucket"
                    name="calls"
                    x={times}
                    sync={CALLS_SYNC}
                />
            }
        />,
        <Kpi
            key="live"
            label="live sessions"
            value={
                <>
                    {o.live}
                    {sub(` / ${snap.sessions.length}`)}
                </>
            }
            sub={`${o.hosts} hosts`}
            to={tableLink("sessions", { show: "live" })}
            readout={{ group: CALLS_SYNC, x: times, values: o.liveSeries, unit: "live" }}
            viz={
                <Sparkline
                    values={o.liveSeries}
                    label="sessions alive over the calls window"
                    name="live"
                    x={times}
                    sync={CALLS_SYNC}
                />
            }
        />,
        <Kpi
            key="on"
            label="plugins on"
            value={
                <>
                    {o.enabled}
                    {sub(` / ${snap.plugins.length}`)}
                </>
            }
            sub={`${snap.plugins.length - o.enabled} disabled`}
            to={tableLink("plugins", { show: "on" })}
            viz={<Bitset items={snap.plugins} />}
        />,
    ];
    return (
        <div className="flex flex-col gap-3">
            {(u.alerts ?? []).map((a) => (
                <div
                    key={a}
                    role="alert"
                    className="glass flex items-center gap-2 border-warn/50 px-3 py-2 text-xs"
                >
                    <Pill tone="warn">alert</Pill>
                    {a}
                </div>
            ))}
            <Saved o={o} />
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 2xl:grid-cols-6">{kpis}</div>
            <div className="grid grid-cols-1 gap-3 xl:grid-cols-12">
                <CallsPanel o={o} calls={snap.calls.length} />
                <Panel
                    title="token mix"
                    hint={`${fmt(tokenTotal(u))} tokens in the ledger window`}
                    className="xl:col-span-4"
                >
                    <TokenMix tokens={u} />
                </Panel>
                <SavingsTrend days={u.savings} className="xl:col-span-12" />
                <Panel
                    title="savings by plugin"
                    hint="Σ est before − after, measured plugins only"
                    action={
                        <span className="flex items-center gap-3">
                            <ExportButtons
                                label="savings by plugin"
                                rows={o.measured}
                                columns={columns}
                            />
                            <PanelLink to="/plugins">plugins →</PanelLink>
                        </span>
                    }
                    className="xl:col-span-7"
                >
                    <DataTable
                        label="savings by plugin"
                        rows={o.measured}
                        columns={columns}
                        getRowId={(r) => r.plugin.id}
                        height={Math.min(320, 36 * Math.max(1, o.measured.length))}
                        empty={
                            <Empty
                                title="No measured savings yet"
                                hint="Plugins fill this in as they record Measurement rows."
                            />
                        }
                    />
                </Panel>
                <DoctorPanel o={o} />
                <SessionsPanel o={o} />
            </div>
        </div>
    );
}
