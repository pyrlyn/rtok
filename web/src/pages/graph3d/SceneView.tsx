// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { lazy, type ReactNode, type RefObject, Suspense, useRef, useState } from "react";
import { Empty } from "../../states";
import { Button } from "../../ui/Button";
import { Chip } from "../../ui/Chip";
import { tooltipBox } from "../../ui/cx";
import Scene2D from "./Scene2D";
import { type Scene, signature } from "./scene";
import { useLayout } from "./useLayout";
import { type Hover, type ViewApi, type ViewEvents, webglAvailable } from "./webgl";

// Three.js is a separate chunk: a page that never draws in 3D never downloads it.
const Scene3D = lazy(() => import("./Scene3D"));

export type View = "3d" | "2d" | "list";
export const VIEW_KEY = "rtok.graph.view";
const VIEWS: { id: View; label: string }[] = [
    { id: "3d", label: "3D" },
    { id: "2d", label: "2D" },
    { id: "list", label: "List" },
];

/** The 2D/3D/list choice survives a reload; storage can be blocked, and then it simply is not kept. */
export function readView(): View {
    try {
        const v = localStorage.getItem(VIEW_KEY);
        if (v === "3d" || v === "2d" || v === "list") return v;
    } catch {
        // Storage is blocked: fall through to the default.
    }
    return "3d";
}

export function saveView(v: View) {
    try {
        localStorage.setItem(VIEW_KEY, v);
    } catch {
        // Not kept; the choice still applies until the page is closed.
    }
}

/** A new scene object only when what is drawn changed, so a snapshot that moves nothing rebuilds nothing. */
export function useStableScene(next: Scene): Scene {
    const kept = useRef(next);
    if (signature(kept.current) !== signature(next)) kept.current = next;
    return kept.current;
}

/**
 * The part of the graph page both levels share: the view switch, the WebGL fallback with its
 * notice, the layout and the hover tooltip. A level brings its scene, its events and its list.
 */
export function SceneView({
    scene,
    api,
    events,
    list,
    toolbar,
    children,
    empty,
}: {
    scene: Scene;
    api: RefObject<ViewApi | null>;
    events: Pick<ViewEvents, "select" | "open" | "menu">;
    /** What the "List" choice shows: the keyboard and screen-reader view of the scene. */
    list: ReactNode;
    /** Controls that sit on the row of the view switch. */
    toolbar?: ReactNode;
    /** Status lines between the switch and the picture. */
    children?: ReactNode;
    /** The line shown when a filter leaves nothing to draw. */
    empty: string;
}) {
    const [choice, setChoice] = useState<View>(readView);
    const [canGl] = useState(webglAvailable);
    const [lost, setLost] = useState<string | null>(null);
    const [tip, setTip] = useState<Hover | null>(null);
    const positions = useLayout(scene);

    const view: View = choice === "3d" && (!canGl || lost !== null) ? "2d" : choice;
    const why = lost ?? (canGl ? null : "WebGL is not available in this browser");
    const pick = (v: View) => {
        setChoice(v);
        saveView(v);
        setTip(null);
    };

    return (
        <>
            <div className="flex flex-wrap items-end gap-3">
                {toolbar}
                <div role="group" aria-label="view" className="ml-auto flex gap-1">
                    {VIEWS.map((v) => (
                        <Chip
                            key={v.id}
                            pressed={choice === v.id}
                            onPressedChange={() => pick(v.id)}
                        >
                            {v.label}
                        </Chip>
                    ))}
                </div>
                {view !== "list" && (
                    <div className="flex gap-1">
                        {(["fit", "reset"] as const).map((a) => (
                            <Button key={a} onClick={() => api.current?.[a]()}>
                                {a === "fit" ? "Fit all" : "Reset view"}
                            </Button>
                        ))}
                    </div>
                )}
            </div>
            {choice === "3d" && why && (
                <p
                    role="status"
                    className="rounded-md border border-warn/40 bg-warn/10 px-2.5 py-1.5 text-xs text-warn-fg"
                >
                    3D view unavailable ({why}); showing the 2D view.
                </p>
            )}
            {children}
            {view === "list" ? (
                list
            ) : scene.nodes.length === 0 ? (
                <Empty title={empty} />
            ) : (
                <div className="relative h-[28rem] overflow-hidden rounded-md border border-border bg-bg/60">
                    {view === "3d" ? (
                        <Suspense
                            fallback={
                                <p className="p-3 text-xs text-fg-muted">Loading the 3D view…</p>
                            }
                        >
                            <Scene3D
                                scene={scene}
                                positions={positions}
                                api={api}
                                {...events}
                                hover={setTip}
                                onUnavailable={setLost}
                            />
                        </Suspense>
                    ) : (
                        <Scene2D
                            scene={scene}
                            positions={positions}
                            api={api}
                            {...events}
                            hover={setTip}
                        />
                    )}
                </div>
            )}
            {tip && (
                <div
                    role="tooltip"
                    style={{ left: tip.x + 12, top: tip.y + 12 }}
                    className={`${tooltipBox} fixed`}
                >
                    {tip.text}
                </div>
            )}
        </>
    );
}
