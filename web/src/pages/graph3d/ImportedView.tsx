// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Imported } from "../../api/query";
import { Button } from "../../ui/Button";
import { Panel } from "../../ui/Panel";
import { Pill } from "../../ui/Pill";
import { Row, Section } from "./ComparePanel";

/** Symbols listed before "+N more"; the file itself keeps all of them. */
const NODES_SHOWN = 200;

/**
 * A saved graph export shown read-only (T329 §8c): what the file holds, with no control that
 * writes. The server parsed it with `export::read`'s checks and kept nothing; closing it drops it.
 */
export function ImportedView({ view, onClose }: { view: Imported; onClose(): void }) {
    const { export: e } = view;
    const names = new Map(e.projects.map((p) => [p.id, p.name]));
    const exported = e.meta.exported_at
        ? new Date(e.meta.exported_at * 1000).toISOString().slice(0, 16).replace("T", " ")
        : null;
    return (
        <Panel title="export" hint="read-only">
            <div
                role="status"
                className="flex flex-wrap items-center gap-2 rounded-md border border-border-strong px-2.5 py-1.5 text-xs"
            >
                <Pill tone="info">read-only</Pill>
                <span>{`viewing export from ${view.name}`}</span>
                <Button className="ml-auto" onClick={onClose}>
                    Close export
                </Button>
            </div>
            <p className="text-2xs text-fg-subtle">
                {[
                    `${e.meta.level}${e.meta.focus ? ` around ${e.meta.focus}` : ""}`,
                    `scope ${e.meta.scope.join(", ")}`,
                    `rtok ${e.meta.rtok_version}`,
                    exported && `exported ${exported} UTC`,
                    e.meta.redacted ? "paths redacted" : "paths kept",
                ]
                    .filter(Boolean)
                    .join(" · ")}
            </p>
            {e.meta.partial && (
                <p className="text-xs text-fg-muted">
                    Partial: a project was still indexing, not indexed, or could not answer.
                </p>
            )}
            {e.meta.notes.map((n) => (
                <p key={n} className="text-2xs whitespace-pre-line text-fg-subtle">
                    {n}
                </p>
            ))}
            <div className="grid gap-3 md:grid-cols-2">
                <Section title="projects" count={e.projects.length}>
                    {e.projects.map((p) => (
                        <Row key={p.id}>
                            <b className="truncate">{p.name}</b>
                            <Pill>{p.backend}</Pill>
                            <Pill tone={p.health === "ok" ? "ok" : "warn"}>{p.health}</Pill>
                            <span className="ml-auto truncate text-2xs text-fg-subtle">
                                {p.root}
                            </span>
                        </Row>
                    ))}
                </Section>
                <Section title="links" count={e.links.length}>
                    {e.links.map((l) => (
                        <Row key={`${l.from}>${l.to}`}>
                            <span className="truncate">{`${names.get(l.from) ?? l.from} → ${names.get(l.to) ?? l.to}`}</span>
                            <Pill>{l.kind}</Pill>
                            <span className="ml-auto text-2xs text-fg-subtle">{`${l.references} calls`}</span>
                        </Row>
                    ))}
                </Section>
            </div>
            <Section title="symbols" count={e.nodes.length}>
                {e.nodes.slice(0, NODES_SHOWN).map((n) => (
                    <Row key={n.id}>
                        <b className="truncate">{n.name}</b>
                        <Pill>{n.kind}</Pill>
                        <span className="ml-auto truncate text-2xs text-fg-subtle">{`${n.path}:${n.line}`}</span>
                    </Row>
                ))}
                {e.nodes.length > NODES_SHOWN && (
                    <li className="text-2xs text-fg-subtle">{`+${e.nodes.length - NODES_SHOWN} more symbols, all in the file`}</li>
                )}
            </Section>
            {e.nodes.length > 0 && (
                <p className="text-2xs text-fg-subtle">{`${e.edges.length} calls between them`}</p>
            )}
        </Panel>
    );
}
