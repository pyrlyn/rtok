// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { lazy, Suspense, useMemo, useRef, useState } from "react";
import { useDrill } from "../../../api/query";
import type { CallsView, ProjectRow } from "../../../api/snapshot.gen";
import { Empty } from "../../../states";
import { Chip } from "../../../ui/Chip";
import { drillScene, emptyScene } from "../drillScene";
import { PAGE } from "../DrillView";
import { type DrillState, drillRequest, drillVersion } from "../drillState";
import { buildScene, type Scene } from "../scene";
import Scene2D from "../Scene2D";
import { readView, saveView, useStableScene } from "../SceneView";
import { useLayout } from "../useLayout";
import { type ViewApi, webglAvailable } from "../webgl";
import { foldedCounters, liveState } from "./lit";
import { totalCalls } from "./useCalls";

// The 3D chunk loads only when the live part draws in 3D.
const Scene3D = lazy(() => import("../Scene3D"));

type LiveView = "2d" | "3d";

const noop = () => {};
const events = { select: noop, menu: noop, hover: noop };

interface View {
    /** What is drawn: 3D only while WebGL works. */
    drawn: LiveView;
    choice: LiveView;
    choose(v: LiveView): void;
    lost(reason: string): void;
    why: string | null;
}

function Canvas({
    scene,
    store,
    now,
    view,
    scope,
    more = 0,
}: {
    scene: Scene;
    store: CallsView;
    now: number;
    view: View;
    scope: ReadonlySet<string> | null;
    /** Nodes the cap folded away: the calls on them count on the nearest drawn ancestor. */
    more?: number;
}) {
    const positions = useLayout(scene);
    const api = useRef<ViewApi | null>(null);
    const live = useMemo(
        () => liveState(scene, store, now, { scope, more }),
        [scene, store, now, scope, more],
    );
    const folded = foldedCounters(scene, live, more);
    // Until the first call the picture is the static graph, dimmed.
    const idle = totalCalls(store) === 0 && store.running.length === 0;
    return (
        <div className="relative h-80 overflow-hidden rounded-md border border-border bg-bg/60">
            {scene.nodes.length === 0 ? (
                <Empty title="Nothing to draw" />
            ) : (
                <div className={`size-full ${idle ? "opacity-40" : ""}`}>
                    {view.drawn === "3d" ? (
                        <Suspense
                            fallback={
                                <p className="p-3 text-xs text-fg-muted">Loading the 3D view…</p>
                            }
                        >
                            <Scene3D
                                scene={scene}
                                positions={positions}
                                api={api}
                                live={live}
                                onUnavailable={view.lost}
                                {...events}
                            />
                        </Suspense>
                    ) : (
                        <Scene2D
                            scene={scene}
                            positions={positions}
                            api={api}
                            live={live}
                            {...events}
                        />
                    )}
                </div>
            )}
            <div role="group" aria-label="live view" className="absolute top-2 left-2 flex gap-1">
                {(["2d", "3d"] as const).map((v) => (
                    <Chip
                        key={v}
                        pressed={view.choice === v}
                        onPressedChange={() => view.choose(v)}
                    >
                        {v === "2d" ? "2D" : "3D"}
                    </Chip>
                ))}
            </div>
            {idle && (
                <p
                    role="status"
                    className="absolute inset-0 grid place-items-center text-xs text-fg-muted"
                >
                    Waiting for graph calls
                </p>
            )}
            {view.choice === "3d" && view.why && (
                <p
                    role="status"
                    className="absolute bottom-2 left-2 rounded-md border border-warn/40 bg-warn/10 px-2 py-1 text-2xs text-warn-fg"
                >
                    3D unavailable ({view.why}); showing 2D
                </p>
            )}
            {folded.length > 0 && (
                <ul
                    aria-label="folded calls"
                    className="absolute right-2 bottom-2 flex flex-col items-end gap-1 text-2xs"
                >
                    {folded.map((f) => (
                        <li
                            key={f.label}
                            className="rounded-md border border-border bg-bg/80 px-2 py-1 text-fg-muted"
                        >
                            {f.label} · {f.calls} folded
                        </li>
                    ))}
                </ul>
            )}
            {live.busy > 0 && (
                <p
                    role="status"
                    className="absolute top-2 right-2 rounded-md border border-warn/40 bg-warn/10 px-2 py-1 text-2xs text-warn-fg motion-safe:animate-pulse"
                >
                    busy: {live.busy} more
                </p>
            )}
        </div>
    );
}

function Overview({
    rows,
    store,
    now,
    view,
    scope,
}: {
    rows: ProjectRow[];
    store: CallsView;
    now: number;
    view: View;
    scope: ReadonlySet<string> | null;
}) {
    const scene = useStableScene(buildScene(rows, { query: "", scopeOnly: false }));
    return <Canvas scene={scene} store={store} now={now} view={view} scope={scope} />;
}

function Drilled({
    state,
    rows,
    store,
    now,
    view,
    scope,
}: {
    state: DrillState;
    rows: ProjectRow[];
    store: CallsView;
    now: number;
    view: View;
    scope: ReadonlySet<string> | null;
}) {
    const ids = useRef(new Map<string, number>()).current;
    const idOf = (id: string) => ids.get(id) ?? (ids.set(id, ids.size + 1), ids.size);
    const row = rows.find((r) => String(r.id) === state.project);
    const graph = useDrill(drillRequest(state, PAGE), drillVersion(row)).data;
    const roots = new Map(rows.map((r) => [r.id, r.root]));
    const scene = useStableScene(
        graph?.state === "ok" ? drillScene(graph, { idOf, roots, selected: null }) : emptyScene,
    );
    const more = graph?.state === "ok" ? graph.more : 0;
    return <Canvas scene={scene} store={store} now={now} view={view} scope={scope} more={more} />;
}

/** Part 2 of the graph page: the level part 1 shows, drawn read-only and lit by the calls arriving. */
export function LiveGraph({
    rows,
    drill,
    store,
    now,
    scope,
}: {
    rows: ProjectRow[];
    drill: DrillState | null;
    store: CallsView;
    now: number;
    scope: ReadonlySet<string> | null;
}) {
    // The page's remembered choice starts it; "list" has no picture, so the live part draws 2D.
    const [choice, setChoice] = useState<LiveView>(() => (readView() === "3d" ? "3d" : "2d"));
    const [canGl] = useState(webglAvailable);
    const [lost, setLost] = useState<string | null>(null);
    const why = lost ?? (canGl ? null : "WebGL is not available in this browser");
    const view: View = {
        drawn: choice === "3d" && why === null ? "3d" : "2d",
        choice,
        why,
        lost: setLost,
        choose: (v) => {
            setChoice(v);
            saveView(v);
        },
    };
    // Another project is a new picture: the layout starts over, as in part 1.
    return drill ? (
        <Drilled
            key={drill.project}
            state={drill}
            rows={rows}
            store={store}
            now={now}
            view={view}
            scope={scope}
        />
    ) : (
        <Overview rows={rows} store={store} now={now} view={view} scope={scope} />
    );
}
