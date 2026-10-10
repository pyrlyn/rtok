// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { type RefObject, useRef, useState } from "react";
import { useDrill } from "../../api/query";
import type { DrillGraph, DrillHit, DrillNode, ProjectRow } from "../../api/snapshot.gen";
import { Empty } from "../../states";
import { Button } from "../../ui/Button";
import { Chip } from "../../ui/Chip";
import { focusRing } from "../../ui/cx";
import { Panel } from "../../ui/Panel";
import { Pill } from "../../ui/Pill";
import { Result } from "../../ui/Result";
import { Search } from "../../ui/Search";
import { Spinner } from "../../ui/Spinner";
import { alertedIds } from "./alerts";
import { Hits, NodeDetails } from "./DrillPanel";
import { drillScene, emptyScene } from "./drillScene";
import {
    breadcrumb,
    DEPTH_MAX,
    type DrillState,
    focusOn,
    openSymbol,
    toggleFile,
} from "./drillState";
import { SceneView, useStableScene } from "./SceneView";
import type { ViewApi } from "./webgl";

/** Nodes the server shows before "+N more"; each click on the control raises the cap by this much. */
export const PAGE = 500;

/** The canvas has no control that can hold a spinner, so a line says a request is out. */
function Busy({ children }: { children: string }) {
    return (
        <p role="status" aria-busy className="flex items-center gap-1.5 text-xs text-fg-muted">
            <span className="text-accent">
                <Spinner size="sm" />
            </span>
            {children}
        </p>
    );
}

function NodeList({
    graph,
    select,
    selected,
    alerted,
}: {
    graph: DrillGraph;
    select(id: string): void;
    selected: string | null;
    /** Projects with an alert: their outlines say so in words, as the canvas badge is colour. */
    alerted: ReadonlySet<number>;
}) {
    return (
        <ul aria-label="symbol graph" className="flex max-h-[28rem] flex-col gap-1 overflow-auto">
            {graph.nodes.map((n) => (
                <li key={n.id}>
                    <button
                        type="button"
                        aria-pressed={n.id === selected}
                        onClick={() => select(n.id)}
                        className={`${focusRing} flex w-full flex-wrap items-center gap-2 rounded-md border border-border px-2.5 py-1.5 text-left text-xs hover:border-border-strong aria-pressed:border-accent/50 aria-pressed:bg-accent/10`}
                    >
                        <b className="truncate">{n.label}</b>
                        <Pill>{n.kind}</Pill>
                        {n.stale && <Pill tone="warn">stale</Pill>}
                        {n.kind === "external" && alerted.has(n.project) && (
                            <Pill tone="fail">alert</Pill>
                        )}
                        <span className="ml-auto truncate text-2xs text-fg-subtle">{n.path}</span>
                    </button>
                </li>
            ))}
        </ul>
    );
}

/** Level 2 of the graph page (T329 §8a): one project's files and symbols, drilled from the overview. */
export function DrillView({
    state,
    rows,
    go,
    probe,
}: {
    state: DrillState;
    rows: ProjectRow[];
    go(next: DrillState | null): void;
    probe?: RefObject<ViewApi | null>;
}) {
    const row = rows.find((r) => String(r.id) === state.project);
    const [limit, setLimit] = useState(PAGE);
    const [selected, setSelected] = useState<string | null>(null);
    // What is typed is not sent until Enter: each key would otherwise be a request.
    const [typed, setTyped] = useState("");
    const [asked, setAsked] = useState("");
    // The layout keeps the position of an id it has seen, so a string id keeps its number across frames.
    const ids = useRef(new Map<string, number>()).current;
    const own = useRef<ViewApi | null>(null);
    const api = probe ?? own;
    const idOf = (id: string) => ids.get(id) ?? (ids.set(id, ids.size + 1), ids.size);

    // The index numbers are the version: when watch re-indexes, the page asks again.
    const ix = row?.index;
    const q = useDrill(
        {
            project: state.project,
            expand: state.expand,
            focus: state.focus,
            depth: state.focus ? state.depth : null,
            limit,
            query: asked,
        },
        [ix?.rows, ix?.files, ix?.pending, ix?.indexed_at],
    );
    const graph = q.data;
    const roots = new Map(rows.map((r) => [r.id, r.root]));
    const alerted = alertedIds(rows);
    // With nothing picked, the panel follows the focus, so a hit that was just opened is the one shown.
    const focused =
        state.focus &&
        graph?.nodes.find(
            (n) =>
                n.path === state.focus!.path &&
                n.label === state.focus!.name &&
                n.project === graph.project &&
                n.kind !== "file",
        );
    const shown = graph?.nodes.find((n) => n.id === selected) ?? focused ?? undefined;
    const scene = useStableScene(
        graph?.state === "ok"
            ? drillScene(graph, {
                  idOf,
                  roots,
                  selected: shown?.id ?? null,
                  alerted,
              })
            : emptyScene,
    );

    const openNode = (n: DrillNode) => go(openSymbol(n.project, { path: n.path, name: n.label }));
    const click = (id: string) => {
        const n = graph?.nodes.find((x) => x.id === id);
        if (!n) return;
        if (n.kind === "external") return openNode(n);
        setSelected(id);
        // The second click on the selected node is the step into it.
        if (id !== selected) return;
        if (n.kind === "file") go(toggleFile(state, n.path));
        else if (n.kind === "function") go(focusOn(state, { path: n.path, name: n.label }));
    };
    const byNumber = (num: number) => graph?.nodes.find((n) => idOf(n.id) === num)?.id;
    // A hit in this project focuses its symbol here; one in a linked project opens that project with it focused.
    const pick = (h: DrillHit) => {
        setSelected(null);
        const focus = { path: h.path, name: h.name };
        go(
            String(h.project) === state.project
                ? focusOn(state, focus)
                : openSymbol(h.project, focus),
        );
    };
    const names = new Map(rows.map((r) => [r.id, r.name]));
    const crumbs = breadcrumb(state, graph?.name ?? row?.name ?? `project ${state.project}`);

    return (
        <Panel title="symbol graph" hint={graph ? `${graph.nodes.length} nodes` : undefined}>
            <nav aria-label="breadcrumb">
                <ol className="flex flex-wrap items-center gap-1.5 text-xs">
                    {crumbs.map((c, i) => (
                        <li key={c.label} className="flex items-center gap-1.5">
                            {i > 0 && <span aria-hidden="true">/</span>}
                            {c.to !== undefined ? (
                                <button
                                    type="button"
                                    onClick={() => go(c.to!)}
                                    className={`${focusRing} text-accent-fg underline`}
                                >
                                    {c.label}
                                </button>
                            ) : (
                                <span aria-current="page" className="font-semibold">
                                    {c.label}
                                </span>
                            )}
                        </li>
                    ))}
                </ol>
            </nav>
            {q.error && (
                <Result verb="graph" kind="error">
                    {q.error.message}
                </Result>
            )}
            {!graph ? (
                !q.error && <Busy>Reading the index…</Busy>
            ) : graph.state === "not indexed" ? (
                <Empty
                    title="Not indexed yet"
                    hint={`Run \`rtok graph index --project ${graph.project}\`; the page fills in when the index lands.`}
                />
            ) : graph.state === "missing" ? (
                <Empty title="The directory is missing" hint={graph.root} />
            ) : (
                <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_20rem]">
                    <div className="flex min-w-0 flex-col gap-2">
                        <SceneView
                            scene={scene}
                            api={api}
                            empty="Nothing to draw"
                            events={{
                                select: (num) => {
                                    const id = byNumber(num);
                                    if (id) click(id);
                                },
                                menu: () => {},
                            }}
                            list={
                                <NodeList
                                    graph={graph}
                                    select={click}
                                    selected={shown?.id ?? null}
                                    alerted={alerted}
                                />
                            }
                            toolbar={
                                <>
                                    <div className="w-56">
                                        <Search
                                            label="search symbols"
                                            placeholder="name, then Enter"
                                            value={typed}
                                            onChange={setTyped}
                                            onEnter={() => setAsked(typed.trim())}
                                        />
                                    </div>
                                    {state.focus && (
                                        <div
                                            role="group"
                                            aria-label="depth"
                                            className="flex items-center gap-1"
                                        >
                                            {Array.from({ length: DEPTH_MAX }, (_, i) => i + 1).map(
                                                (d) => (
                                                    <Chip
                                                        key={d}
                                                        pressed={state.depth === d}
                                                        onPressedChange={() =>
                                                            go({ ...state, depth: d })
                                                        }
                                                    >
                                                        {`depth ${d}`}
                                                    </Chip>
                                                ),
                                            )}
                                        </div>
                                    )}
                                </>
                            }
                        >
                            {q.isFetching && <Busy>Updating…</Busy>}
                            {graph.partial && (
                                <Result verb="index" kind="warn">
                                    Partial: some files changed since the last index run, so this
                                    picture lags the tree.
                                </Result>
                            )}
                            {graph.more > 0 && (
                                <Button
                                    pending={q.isFetching}
                                    onClick={() => setLimit(limit + PAGE)}
                                >
                                    {`+${graph.more} more`}
                                </Button>
                            )}
                        </SceneView>
                    </div>
                    <aside
                        aria-label="node details"
                        className="flex min-w-0 flex-col gap-3 rounded-md border border-border p-2.5"
                    >
                        {asked && !q.isPlaceholderData && (
                            <section aria-label="search results" className="flex flex-col gap-1">
                                <h3 className="text-2xs font-semibold text-fg-muted">{`results for "${asked}"`}</h3>
                                <Hits hits={graph.hits} names={names} pick={pick} />
                            </section>
                        )}
                        <NodeDetails
                            graph={graph}
                            node={shown}
                            rows={rows}
                            select={setSelected}
                            open={openNode}
                        />
                    </aside>
                </div>
            )}
        </Panel>
    );
}
