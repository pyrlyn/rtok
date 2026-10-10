// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { type RefObject, useEffect, useRef, useState } from "react";
import { useProjectMutation } from "../../api/query";
import type { ProjectRow } from "../../api/snapshot.gen";
import { Kpi } from "../../ui/Kpi";
import { Panel } from "../../ui/Panel";
import { Result } from "../../ui/Result";
import { Search } from "../../ui/Search";
import { Spinner } from "../../ui/Spinner";
import { Switch } from "../../ui/Switch";
import { ProjectList } from "./ProjectList";
import { buildScene } from "./scene";
import { SceneView, useStableScene } from "./SceneView";
import { prefersReducedMotion, type ViewApi } from "./webgl";

export { VIEW_KEY } from "./SceneView";

/** Level 1 of the graph page (T329 §8a): the registered projects and their links. */
export function ProjectsOverview({
    rows,
    probe,
    onOpen,
}: {
    rows: ProjectRow[];
    /** Hands the active view's API to a test, which has no other way to learn where a node is on screen. */
    probe?: RefObject<ViewApi | null>;
    /** Drills into a project (T329.22): the node menu's Open and a double-click. */
    onOpen?(id: number): void;
}) {
    const [query, setQuery] = useState("");
    const [scopeOnly, setScopeOnly] = useState(false);
    const [menu, setMenu] = useState<{ id: number; x: number; y: number } | null>(null);
    const scene = useStableScene(buildScene(rows, { query, scopeOnly }));
    const own = useRef<ViewApi | null>(null);
    const api = probe ?? own;
    const { mutate, error, inFlight } = useProjectMutation();
    const waiting = new Set(
        inFlight.flatMap((r) => (r.action === "select" ? [Number(r.project)] : [])),
    );

    const select = (id: number) => {
        const p = rows.find((r) => r.id === id);
        if (p && !p.missing) mutate({ action: "select", project: String(id) });
    };
    const open = (id: number) => {
        // A missing project has no index to draw.
        if (rows.find((r) => r.id === id)?.missing === false) onOpen?.(id);
    };
    const events = {
        select,
        ...(onOpen && { open }),
        menu: (id: number, x: number, y: number) => setMenu({ id, x, y }),
    };

    return (
        <Panel title="project graph" hint={`${scene.counts.total} projects`}>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                <Kpi label="projects" value={String(scene.counts.total)} />
                <Kpi label="in scope" value={String(scene.counts.inScope)} />
                <Kpi label="linked pairs" value={String(scene.counts.pairs)} />
                <Kpi
                    label="problems"
                    value={String(scene.counts.problems)}
                    tone={scene.counts.problems ? "warn" : "ok"}
                    sub="missing or failed"
                />
            </div>
            <SceneView
                scene={scene}
                api={api}
                events={events}
                empty="No project matches"
                list={<ProjectList scene={scene} select={select} waiting={waiting} />}
                toolbar={
                    <>
                        <div className="w-48">
                            <Search label="filter projects" value={query} onChange={setQuery} />
                        </div>
                        <label className="flex items-center gap-1.5 text-xs text-fg-muted">
                            <Switch
                                label="only the current scope"
                                checked={scopeOnly}
                                onCheckedChange={setScopeOnly}
                            />
                            only scope
                        </label>
                    </>
                }
            >
                {waiting.size > 0 && (
                    // The canvas and the menu have no control that can hold the spinner.
                    <p
                        role="status"
                        aria-busy
                        className="flex items-center gap-1.5 text-xs text-fg-muted"
                    >
                        <span className="text-accent">
                            <Spinner size="sm" />
                        </span>
                        Selecting {rows.find((r) => waiting.has(r.id))?.name ?? "project"}…
                    </p>
                )}
                {error && (
                    <Result verb="select" kind="error">
                        {error.message}
                    </Result>
                )}
                {rows.length === 1 && (
                    <p className="text-2xs text-fg-subtle">
                        One project so far. Link another with `rtok graph projects link
                        &lt;project&gt;` to see edges.
                    </p>
                )}
            </SceneView>
            {menu && (
                <NodeMenu
                    at={menu}
                    node={rows.find((r) => r.id === menu.id)}
                    onClose={() => setMenu(null)}
                    onSelect={() => select(menu.id)}
                    onOpen={onOpen && (() => open(menu.id))}
                    onFocus={() => api.current?.focus(menu.id)}
                />
            )}
        </Panel>
    );
}

/** What the protocol offers today: open (T329.22), select, focus, copy the root. Link, unlink, re-index and remove arrive with T329.20. */
function NodeMenu({
    at,
    node,
    onClose,
    onSelect,
    onOpen,
    onFocus,
}: {
    at: { x: number; y: number };
    node: ProjectRow | undefined;
    onClose(): void;
    onSelect(): void;
    onOpen?(): void;
    onFocus(): void;
}) {
    const ref = useRef<HTMLDivElement>(null);
    useEffect(() => {
        ref.current?.querySelector("button")?.focus();
        const away = (e: Event) => !ref.current?.contains(e.target as Node) && onClose();
        const key = (e: KeyboardEvent) => e.key === "Escape" && onClose();
        document.addEventListener("pointerdown", away);
        document.addEventListener("keydown", key);
        return () => {
            document.removeEventListener("pointerdown", away);
            document.removeEventListener("keydown", key);
        };
    }, [onClose]);
    if (!node) return null;
    const item =
        "block w-full px-3 py-1.5 text-left text-xs transition-colors duration-fast hover:bg-surface-3 disabled:opacity-40";
    const act = (fn: () => void) => () => (fn(), onClose());
    return (
        <div
            ref={ref}
            role="menu"
            aria-label={`${node.name} actions`}
            style={{ left: at.x, top: at.y }}
            className="fixed z-40 min-w-36 rounded-md border border-border-strong bg-surface py-1 shadow-e3"
        >
            <button
                type="button"
                role="menuitem"
                disabled={node.missing}
                className={item}
                onClick={act(onSelect)}
            >
                Select
            </button>
            {onOpen && (
                <button
                    type="button"
                    role="menuitem"
                    disabled={node.missing}
                    className={item}
                    onClick={act(onOpen)}
                >
                    Open
                </button>
            )}
            <button type="button" role="menuitem" className={item} onClick={act(onFocus)}>
                {prefersReducedMotion() ? "Center" : "Fly to"}
            </button>
            <button
                type="button"
                role="menuitem"
                className={item}
                onClick={act(() => void navigator.clipboard?.writeText(node.root).catch(() => {}))}
            >
                Copy path
            </button>
        </div>
    );
}
