// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Alert, ProjectRow } from "../../api/snapshot.gen";
import { Panel } from "../../ui/Panel";
import { Pill } from "../../ui/Pill";
import { type ToastItem, Toasts } from "../../ui/Toasts";
import { alertsOf, describeGroup, diffAlerts, groupAlerts, toastsFor, WORDS } from "./alerts";

/** A toast for each alert a snapshot raised or cleared, judged against the snapshot before it. */
function useAlertToasts(alerts: Alert[]) {
    const [items, setItems] = useState<ToastItem[]>([]);
    // `null` until the first snapshot: alerts that were already up when the page opened are the list's job.
    const before = useRef<Alert[] | null>(null);
    const seq = useRef(0);
    useEffect(() => {
        const prev = before.current;
        before.current = alerts;
        if (!prev) return;
        const fresh = toastsFor(diffAlerts(prev, alerts)).map((t) => ({ ...t, id: ++seq.current }));
        if (fresh.length) setItems((cur) => [...cur, ...fresh]);
    }, [alerts]);
    const dismiss = useCallback(
        (id: number) => setItems((cur) => cur.filter((t) => t.id !== id)),
        [],
    );
    return { items, dismiss };
}

/** The alerts list of the graph page (T329 §8d) and the toasts for what changed since the last snapshot. */
export function GraphAlerts({ rows }: { rows: ProjectRow[] }) {
    const alerts = useMemo(() => alertsOf(rows), [rows]);
    const groups = useMemo(() => groupAlerts(alerts), [alerts]);
    const { items, dismiss } = useAlertToasts(alerts);
    return (
        <>
            {groups.length > 0 && (
                <Panel title="alerts" hint={`${alerts.length} active`}>
                    <ul
                        aria-label="alerts"
                        className="flex flex-col divide-y divide-border/60 text-xs"
                    >
                        {groups.map((g) => (
                            <li key={g.kind} className="flex flex-wrap items-center gap-2 py-2">
                                <Pill tone="fail" dot>
                                    {WORDS[g.kind]}
                                </Pill>
                                <span className="min-w-0 break-words">{describeGroup(g)}</span>
                            </li>
                        ))}
                    </ul>
                </Panel>
            )}
            <Toasts items={items} onDismiss={dismiss} />
        </>
    );
}
