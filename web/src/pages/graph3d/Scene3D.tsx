// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { type RefObject, useEffect, useRef } from "react";
import type { LiveState } from "./live/lit";
import type { Scene } from "./scene";
import { Stage } from "./stage3d";
import type { Positions } from "./useLayout";
import { prefersReducedMotion, type ViewApi, type ViewEvents } from "./webgl";

export interface View3DProps extends ViewEvents {
    scene: Scene;
    positions: Positions;
    api: RefObject<ViewApi | null>;
    /** The 3D view cannot run (no context, or it was lost): the page switches to 2D and says why. */
    onUnavailable(reason: string): void;
    /** The read-only live picture (T329 §8b): no orbit and no picking, lit by the calls, framed on the running ones. */
    live?: LiveState;
}

/** The focus travels as a comma-joined key, so a new array with the same ids does not move the camera. */
const frame = (s: Stage, key: string) => s.frameOn(key ? key.split(",").map(Number) : []);

/** The React shell around [`Stage`]: it builds the stage once, feeds it scenes, and gives it back on unmount. */
export default function Scene3D({
    scene,
    positions,
    api,
    select,
    open,
    menu,
    hover,
    onUnavailable,
    live,
}: View3DProps) {
    const host = useRef<HTMLDivElement>(null);
    const stage = useRef<Stage | null>(null);
    // The stage is created once; handlers it calls always see the latest props.
    const events = useRef({ select, open, menu, hover, onUnavailable });
    events.current = { select, open, menu, hover, onUnavailable };
    const latest = useRef(live);
    latest.current = live;
    const focus = live?.focus.join(",") ?? "";

    useEffect(() => {
        let made: Stage;
        try {
            made = new Stage(
                host.current!,
                {
                    select: (id) => events.current.select(id),
                    ...(events.current.open && { open: (id) => events.current.open?.(id) }),
                    menu: (id, x, y) => events.current.menu(id, x, y),
                    hover: (h) => events.current.hover(h),
                },
                positions,
                prefersReducedMotion(),
                (why) => events.current.onUnavailable(why),
                latest.current !== undefined,
            );
        } catch (e) {
            events.current.onUnavailable(e instanceof Error ? e.message : "WebGL is not available");
            return;
        }
        stage.current = made;
        api.current = made;
        // A stage made after the live state arrived (the layout restarted) starts in step with it.
        made.setLive(latest.current);
        frame(made, latest.current?.focus.join(",") ?? "");
        return () => {
            made.dispose();
            stage.current = null;
            api.current = null;
        };
    }, [positions, api]);

    useEffect(() => stage.current?.setScene(scene), [scene]);
    useEffect(() => stage.current?.setLive(live), [live]);
    useEffect(() => {
        if (stage.current) frame(stage.current, focus);
    }, [focus]);

    return (
        <div
            ref={host}
            className="size-full min-h-72"
            data-testid={live ? "graph-live-3d" : "graph-3d"}
            // A canvas has no DOM to inspect: the ids the camera holds are the one thing a test can read.
            data-framed={focus || undefined}
        />
    );
}
