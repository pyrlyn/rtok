// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { lazy, Suspense, useMemo, useRef, useState } from "react";
import { useDrill } from "../../../api/query";
import type { ProjectRow } from "../../../api/snapshot.gen";
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
import type { CallsStore } from "./callsStore";
import { liveState } from "./lit";

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
}: {
    scene: Scene;
    store: CallsStore;
    now: number;
    view: View;
}) {
    const positions = useLayout(scene);
    const api = useRef<ViewApi | null>(null);
    const live = useMemo(() => liveState(scene, store, now), [scene, store, now]);
    // Until the first call the picture is the static graph, dimmed.
    const idle = store.all.calls === 0 && store.running.length === 0;
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
}: {
    rows: ProjectRow[];
    store: CallsStore;
    now: number;
    view: View;
}) {
    const scene = useStableScene(buildScene(rows, { query: "", scopeOnly: false }));
    return <Canvas scene={scene} store={store} now={now} view={view} />;
}

function Drilled({
    state,
    rows,
    store,
    now,
    view,
}: {
    state: DrillState;
    rows: ProjectRow[];
    store: CallsStore;
    now: number;
    view: View;
}) {
    const ids = useRef(new Map<string, number>()).current;
    const idOf = (id: string) => ids.get(id) ?? (ids.set(id, ids.size + 1), ids.size);
    const row = rows.find((r) => String(r.id) === state.project);
    const graph = useDrill(drillRequest(state, PAGE), drillVersion(row)).data;
    const roots = new Map(rows.map((r) => [r.id, r.root]));
    const scene = useStableScene(
        graph?.state === "ok" ? drillScene(graph, { idOf, roots, selected: null }) : emptyScene,
    );
    return <Canvas scene={scene} store={store} now={now} view={view} />;
}

/** Part 2 of the graph page: the level part 1 shows, drawn read-only and lit by the calls arriving. */
export function LiveGraph({
    rows,
    drill,
    store,
    now,
}: {
    rows: ProjectRow[];
    drill: DrillState | null;
    store: CallsStore;
    now: number;
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
        />
    ) : (
        <Overview rows={rows} store={store} now={now} view={view} />
    );
}
