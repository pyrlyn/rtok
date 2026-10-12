// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { type RefObject, useEffect, useMemo, useRef } from "react";
import { millis, seconds } from "../../format";
import type { ViewApi } from "../webgl";
import { accentOf, type CallLabel, LABEL_HOLD_MS } from "./lit";

/** Lines drawn on one node; the rest are counted, so a busy node does not cover its neighbours. */
export const MAX_LINES = 3;
/** Gap between a node's centre and its label, in screen pixels. */
const GAP = 12;

/** The counter ticks off the page's one-second clock (`now`), the same figure the running list shows. */
export function labelText(l: CallLabel, now: number): string {
    if (l.endedAt === null) return `${l.tool} ${seconds(now - l.at)}`;
    return l.ms === null ? l.tool : `${l.tool} ${millis(l.ms)}`;
}

const byNode = (labels: readonly CallLabel[]) => {
    const groups = new Map<number, CallLabel[]>();
    for (const l of labels) groups.set(l.node, [...(groups.get(l.node) ?? []), l]);
    return [...groups];
};

/**
 * A floating label per call on its node, over the 2D and the 3D canvas alike. Both views answer
 * `screenOf`, so one overlay follows the nodes through the eased camera and the settling layout
 * without knowing which view draws them. It is a picture of what the running list already says,
 * so it stays out of the accessibility tree.
 */
export function RunningLabels({
    labels,
    now,
    api,
}: {
    labels: readonly CallLabel[];
    now: number;
    api: RefObject<ViewApi | null>;
}) {
    const host = useRef<HTMLDivElement>(null);
    const els = useRef(new Map<number, HTMLElement>());
    const groups = useMemo(() => byNode(labels), [labels]);
    // The frame loop restarts only when the set of labelled nodes changes, not every second.
    const nodes = groups.map(([n]) => n).join(",");

    useEffect(() => {
        if (!nodes) return;
        let raf = 0;
        const place = () => {
            const box = host.current?.getBoundingClientRect();
            for (const [node, el] of els.current) {
                let p: { x: number; y: number } | null = null;
                try {
                    p = api.current?.screenOf(node) ?? null;
                } catch {
                    // A view in the middle of being torn down has no projection to give.
                }
                const x = p && box ? p.x - box.left : 0;
                const y = p && box ? p.y - box.top : 0;
                const inside =
                    !!p && !!box && x >= 0 && y >= 0 && x <= box.width && y <= box.height;
                el.style.visibility = inside ? "visible" : "hidden";
                if (!inside || !box) continue;
                // Near the right edge the label goes left of its node instead of off the canvas.
                const left =
                    x + GAP + el.offsetWidth > box.width ? x - GAP - el.offsetWidth : x + GAP;
                el.style.transform = `translate(${Math.max(0, left)}px, ${Math.max(0, y - 8)}px)`;
            }
            raf = requestAnimationFrame(place);
        };
        place();
        return () => cancelAnimationFrame(raf);
    }, [nodes, api]);

    return (
        <div
            ref={host}
            aria-hidden="true"
            data-testid="run-labels"
            className="pointer-events-none absolute inset-0 overflow-hidden"
        >
            {groups.map(([node, list]) => (
                <div
                    key={node}
                    ref={(el) => {
                        if (el) els.current.set(node, el);
                        else els.current.delete(node);
                    }}
                    data-node={node}
                    className="invisible absolute top-0 left-0 flex flex-col items-start gap-0.5"
                >
                    {list.slice(0, MAX_LINES).map((l) => (
                        <Line key={l.call} label={l} now={now} />
                    ))}
                    {list.length > MAX_LINES && (
                        <span className="rounded-md bg-bg/85 px-1.5 text-2xs text-fg-muted">
                            +{list.length - MAX_LINES} calls
                        </span>
                    )}
                </div>
            ))}
        </div>
    );
}

function Line({ label: l, now }: { label: CallLabel; now: number }) {
    const running = l.endedAt === null;
    const fading = !running && now - l.endedAt! >= LABEL_HOLD_MS;
    const accent = l.accent === null ? null : accentOf(l.accent);
    return (
        <span
            data-testid="run-label"
            data-state={running ? "running" : fading ? "fading" : "ended"}
            data-folded={l.folded ? "" : undefined}
            style={
                accent
                    ? { borderColor: accent.color, borderStyle: accent.dash ? "dashed" : "solid" }
                    : undefined
            }
            // Fading only when motion is welcome; with reduced motion the label just goes.
            className={`rounded-md border bg-bg/85 px-1.5 font-mono text-2xs whitespace-nowrap motion-safe:transition-opacity motion-safe:duration-1000 ${
                l.failed ? "border-delta-fg/60 text-delta-fg" : "border-border text-fg"
            } ${fading ? "opacity-0" : "opacity-100"}`}
        >
            {labelText(l, now)}
        </span>
    );
}
