// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { DrillGraph, DrillHit, DrillNode, ProjectRow } from "../../api/snapshot.gen";
import { Button } from "../../ui/Button";
import { focusRing } from "../../ui/cx";
import { Pill } from "../../ui/Pill";
import { editorLink } from "./drillState";

const rowButton = `${focusRing} flex w-full flex-wrap items-center gap-2 rounded-md border border-border px-2 py-1 text-left text-xs hover:border-border-strong`;

/** Callers and callees are the other ends of the `calls` edges at a node; the cap may have left an end out. */
function neighbours(g: DrillGraph, id: string, side: "from" | "to") {
    const by = new Map(g.nodes.map((n) => [n.id, n]));
    const other = side === "from" ? "to" : "from";
    return g.edges.flatMap((e) => {
        const n = e.kind === "calls" && e[side] === id ? by.get(e[other]) : undefined;
        return n ? [{ node: n, count: e.count }] : [];
    });
}

function Related({
    title,
    items,
    select,
}: {
    title: string;
    items: { node: DrillNode; count: number }[];
    select(id: string): void;
}) {
    return (
        <section aria-label={title} className="flex flex-col gap-1">
            <h3 className="text-2xs font-semibold text-fg-muted">{`${title} (${items.length})`}</h3>
            {items.length === 0 ? (
                <p className="text-2xs text-fg-subtle">none in this picture</p>
            ) : (
                <ul className="flex flex-col gap-1">
                    {items.map(({ node, count }) => (
                        <li key={node.id}>
                            <button
                                type="button"
                                onClick={() => select(node.id)}
                                className={rowButton}
                            >
                                <b className="truncate">{node.label}</b>
                                {count > 1 && <span className="text-fg-subtle">{`×${count}`}</span>}
                                <span className="ml-auto truncate text-2xs text-fg-subtle">
                                    {node.path}
                                </span>
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}

/** The selected node's details (T329 §8a): where it is, what it is, who calls it and what it calls. */
export function NodeDetails({
    graph,
    node,
    rows,
    select,
    open,
}: {
    graph: DrillGraph;
    node: DrillNode | undefined;
    rows: ProjectRow[];
    select(id: string): void;
    /** Steps into a symbol of a linked project. */
    open(node: DrillNode): void;
}) {
    if (!node) return <p className="text-xs text-fg-subtle">Select a node to see its details.</p>;
    const external = node.kind === "external";
    // A symbol of a linked project lives under that project's root, not this one's.
    const owner = rows.find((r) => r.id === node.project);
    const root = external ? owner?.root : graph.root;
    return (
        <div className="flex flex-col gap-2 text-xs">
            <div className="flex flex-wrap items-center gap-2">
                <b className="break-all">{node.label}</b>
                <Pill>{node.kind}</Pill>
                {node.stale && <Pill tone="warn">stale</Pill>}
            </div>
            <p className="break-all text-fg-muted">
                {external && owner && <span className="font-semibold">{owner.name}: </span>}
                {node.path}
                {node.line > 0 && <span className="text-fg-subtle">{`:${node.line}`}</span>}
            </p>
            {node.signature && (
                <code className="block rounded-md border border-border bg-bg/60 px-2 py-1 break-all">
                    {node.signature}
                </code>
            )}
            <div className="flex flex-wrap gap-2">
                {root && (
                    <a
                        href={editorLink(root, node.path, node.line)}
                        className={`${focusRing} text-accent-fg underline`}
                    >
                        Open in editor
                    </a>
                )}
                {external && (
                    <Button
                        onClick={() => open(node)}
                    >{`Open in ${owner?.name ?? "project"}`}</Button>
                )}
            </div>
            <Related title="callers" items={neighbours(graph, node.id, "to")} select={select} />
            <Related title="callees" items={neighbours(graph, node.id, "from")} select={select} />
        </div>
    );
}

/** What a search found in the project and its scope, each with the project it lives in. */
export function Hits({
    hits,
    names,
    pick,
}: {
    hits: DrillHit[];
    names: ReadonlyMap<number, string>;
    pick(hit: DrillHit): void;
}) {
    if (hits.length === 0) return <p className="text-xs text-fg-subtle">No symbol matches.</p>;
    return (
        <ul aria-label="search hits" className="flex flex-col gap-1">
            {hits.map((h) => (
                <li key={`${h.project}:${h.path}:${h.line}:${h.name}`}>
                    <button type="button" onClick={() => pick(h)} className={rowButton}>
                        <b className="truncate">{h.name}</b>
                        <Pill>{h.kind}</Pill>
                        <Pill tone="info">{names.get(h.project) ?? `project ${h.project}`}</Pill>
                        <span className="ml-auto truncate text-2xs text-fg-subtle">{`${h.path}:${h.line}`}</span>
                    </button>
                </li>
            ))}
        </ul>
    );
}
