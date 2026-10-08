// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import {
    useCallback,
    useEffect,
    useId,
    useMemo,
    useRef,
    useState,
    type KeyboardEvent,
} from "react";
import { focusRing } from "../ui/cx";
import { setHover, useHover } from "./hover";
import { loadRenderer, type Anchor, type ChartView } from "./renderer";
import { stackAt, type ChartSpec, type Tone } from "./spec";
import { Tooltip } from "./Tooltip";

export const swatch: Record<Tone, string> = {
    accent: "bg-accent-fg",
    "accent-soft": "bg-accent-fg/50",
    muted: "bg-fg-muted/75",
    delta: "bg-delta-fg",
};

const plain = (v: number) => v.toLocaleString();

/** The chart pages use: a labelled, focusable box the renderer draws into, plus our tooltip. */
export function Chart({
    spec,
    className,
    format = plain,
}: {
    spec: ChartSpec;
    className?: string;
    format?: (v: number) => string;
}) {
    const host = useRef<HTMLDivElement>(null);
    const view = useRef<ChartView | null>(null);
    const latest = useRef(spec);
    latest.current = spec;
    const me = useMemo(() => Symbol("chart"), []);
    const tipId = useId();
    const [hovered, setHovered] = useState<number | null>(null);
    const [anchor, setAnchor] = useState<Anchor | null>(null);
    const shared = useHover(spec.sync);
    const sharedNow = useRef(shared);
    sharedNow.current = shared;
    const hoveredNow = useRef(hovered);
    hoveredNow.current = hovered;

    const own = useCallback(
        (i: number | null) => {
            setHovered(i);
            setAnchor(i == null ? null : (view.current?.anchor(i) ?? null));
            const group = latest.current.sync;
            if (group) setHover(group, i == null ? null : { index: i, owner: me });
        },
        [me],
    );

    useEffect(() => {
        let gone = false;
        loadRenderer()
            .then((render) => {
                if (gone || !host.current) return;
                view.current = render(host.current, latest.current, {
                    hover: (i) => {
                        // The renderer may echo a pointer we moved for another chart a frame later.
                        const s = sharedNow.current;
                        if (!(s && s.owner !== me && s.index === i)) own(i);
                    },
                    leave: () => own(null),
                });
            })
            // Fail open: without a renderer the box keeps its accessible label and the page works.
            .catch((e: unknown) => console.warn("chart renderer unavailable", e));
        return () => {
            gone = true;
            view.current?.dispose();
            view.current = null;
            const group = latest.current.sync;
            if (group) setHover(group, null);
        };
    }, [own, me]);

    useEffect(() => {
        view.current?.update(spec);
        // A new frame can move the hovered column; a hover alone must not redraw.
        const i = hoveredNow.current;
        if (i != null) setAnchor(view.current?.anchor(i) ?? null);
    }, [spec]);

    // Another chart in the group owns the hover: show its pointer here, without a tooltip.
    useEffect(() => {
        if (!spec.sync) return;
        if (shared && shared.owner !== me) view.current?.pointer(shared.index);
        else if (!shared) view.current?.pointer(null);
    }, [shared, me, spec.sync]);

    const n = spec.x.length;
    const onKeyDown = (e: KeyboardEvent) => {
        const at = hovered ?? -1;
        const next =
            e.key === "ArrowRight"
                ? Math.min(n - 1, at + 1)
                : e.key === "ArrowLeft"
                  ? Math.max(0, at < 0 ? n - 1 : at - 1)
                  : e.key === "Home"
                    ? 0
                    : e.key === "End"
                      ? n - 1
                      : e.key === "Escape"
                        ? null
                        : undefined;
        if (next === undefined || !n) return;
        e.preventDefault();
        view.current?.pointer(next);
        own(next);
    };

    const tip = hovered != null && anchor && (shared?.owner ?? me) === me;
    return (
        <>
            <div
                ref={host}
                role="img"
                aria-label={spec.label}
                aria-describedby={tip ? tipId : undefined}
                tabIndex={0}
                onKeyDown={onKeyDown}
                onBlur={() => {
                    view.current?.pointer(null);
                    own(null);
                }}
                className={`${focusRing} rounded-md ${className ?? ""}`}
            />
            {tip && (
                <Tooltip id={tipId} anchor={anchor}>
                    <Rows spec={spec} i={hovered} format={format} />
                </Tooltip>
            )}
        </>
    );
}

const NO_DATA = "no data";
const show = (v: number | null | undefined, format: (v: number) => string) =>
    v == null ? NO_DATA : format(v);

function Rows({ spec, i, format }: { spec: ChartSpec; i: number; format: (v: number) => string }) {
    const rows = spec.dots ? [...spec.series, spec.dots] : spec.series;
    const empty = spec.series.every((s) => s.values[i] == null);
    return (
        <>
            <p className="mb-1 flex gap-3 font-semibold text-fg">
                {(spec.titles ?? spec.x)[i]}
                {spec.kind === "stacked-bars" && (
                    <span className="ml-auto tabular-nums">
                        {empty ? NO_DATA : format(stackAt(spec, i))}
                    </span>
                )}
            </p>
            <ul className="flex flex-col gap-0.5">
                {rows.map((s) => (
                    <li key={s.id} className="flex items-center gap-2">
                        <span
                            aria-hidden="true"
                            className={`size-2 rounded-full ${swatch[s.tone]}`}
                        />
                        {s.label}
                        <span className="ml-auto pl-3 text-fg tabular-nums">
                            {show(s.values[i], format)}
                        </span>
                    </li>
                ))}
            </ul>
        </>
    );
}
