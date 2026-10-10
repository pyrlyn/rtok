// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { type ReactNode, useState } from "react";
import { useDiff } from "../../api/query";
import type {
    DiffDef,
    DiffExport,
    DiffMove,
    DiffProject,
    DiffReport,
} from "../../api/snapshot.gen";
import { Button } from "../../ui/Button";
import { focusRing } from "../../ui/cx";
import { Pill } from "../../ui/Pill";
import { Result } from "../../ui/Result";
import { Search } from "../../ui/Search";
import { CHANGE_MARK, CHANGE_TONE, CHANGES, type Change } from "./compare";

/** A saved export is read whole into the page and sent as text, so a very large one is refused here. */
const EXPORT_MAX = 16 << 20;
const CALLERS_SHOWN = 5;

/** Why a file is refused before it is read, or null; sync so the page shows it in the same tick. */
export const tooLarge = (file: File): string | null =>
    file.size > EXPORT_MAX ? `${file.name} is larger than ${EXPORT_MAX >> 20} MiB` : null;

/**
 * Compare mode's state and its request (T329.35). The old side is a ref typed here (sent on Enter,
 * as the symbol search is) or a saved export chosen with the file picker; the page sends the
 * file's text, never a path.
 */
export function useCompare(project: string, version: readonly unknown[]) {
    const [on, setOn] = useState(false);
    const [typed, setTyped] = useState("");
    const [ref, setRef] = useState("");
    const [saved, setSaved] = useState<DiffExport | null>(null);
    const [refused, setRefused] = useState<string | null>(null);
    const request = on
        ? { project, from: ref && !saved ? [ref] : [], to: null, export: saved }
        : null;
    const q = useDiff(request, [version, saved?.name, saved?.text.length]);
    const pick = async (file: File | undefined) => {
        if (!file) return;
        const refusal = tooLarge(file);
        setRefused(refusal);
        if (!refusal) setSaved({ name: file.name, text: await file.text() });
    };
    return {
        on,
        setOn,
        typed,
        setTyped,
        send: () => setRef(typed.trim()),
        saved,
        setSaved,
        refused,
        pick,
        q,
    };
}

export type Compare = ReturnType<typeof useCompare>;

/** The member the page is looking at; a linked project has its own entry. */
export const projectOf = (r: DiffReport | undefined, name: string): DiffProject | undefined =>
    r?.projects.find((p) => p.project === name) ??
    (r?.projects.length === 1 ? r.projects[0] : undefined);

export function Section({
    title,
    count,
    children,
}: {
    title: string;
    count: number;
    children: ReactNode;
}) {
    if (count === 0) return null;
    return (
        <section aria-label={title} className="flex flex-col gap-1">
            <h3 className="text-2xs font-semibold text-fg-muted">{`${title} (${count})`}</h3>
            <ul className="flex flex-col gap-1">{children}</ul>
        </section>
    );
}

export const Row = ({ children }: { children: ReactNode }) => (
    <li className="flex flex-wrap items-center gap-1.5 rounded-md border border-border px-2 py-1 text-xs">
        {children}
    </li>
);

const where = (d: DiffDef) => (
    <span className="ml-auto truncate text-2xs text-fg-subtle">{`${d.path}:${d.line}`}</span>
);

function Def({ d, change, extra }: { d: DiffDef; change: Change; extra?: ReactNode }) {
    const callers = d.callers ?? [];
    return (
        <Row>
            <Pill tone={CHANGE_TONE[change]}>{CHANGE_MARK[change]}</Pill>
            <b className="truncate">{d.name}</b>
            <Pill>{d.kind}</Pill>
            {extra}
            {where(d)}
            {callers.length > 0 && (
                <ul
                    aria-label={`callers of ${d.name}`}
                    className="w-full pl-4 text-2xs text-fg-muted"
                >
                    {callers.slice(0, CALLERS_SHOWN).map((c) => (
                        <li key={c} className="truncate">
                            {c}
                        </li>
                    ))}
                    {callers.length > CALLERS_SHOWN && (
                        <li>{`+${callers.length - CALLERS_SHOWN} more callers`}</li>
                    )}
                </ul>
            )}
        </Row>
    );
}

function Moves({ title, rows, change }: { title: string; rows: DiffMove[]; change: Change }) {
    return (
        <Section title={title} count={rows.length}>
            {rows.map((m) => (
                <Row key={`${m.from.path}:${m.from.name}`}>
                    <Pill tone={CHANGE_TONE[change]}>{CHANGE_MARK[change]}</Pill>
                    <b className="truncate">
                        {m.from.name === m.to.name ? m.to.name : `${m.from.name} → ${m.to.name}`}
                    </b>
                    <span className="ml-auto truncate text-2xs text-fg-subtle">
                        {m.from.path === m.to.path
                            ? `${m.to.path}:${m.to.line}`
                            : `${m.from.path} → ${m.to.path}`}
                    </span>
                </Row>
            ))}
        </Section>
    );
}

/** The counts a reader checks against `rtok graph diff`; each carries its mark, so no colour is needed to tell them apart. */
function counts(d: DiffProject): Record<Change, number> {
    return {
        added: d.added.length,
        removed: d.removed.length,
        changed: d.changed.length + d.renamed.length,
        moved: d.moved.length,
    };
}

function Changes({ report, d }: { report: DiffReport; d: DiffProject }) {
    const n = counts(d);
    const none =
        Object.values(n).every((c) => c === 0) &&
        d.edges_added.length + d.edges_removed.length + d.not_analysed.length === 0;
    return (
        <div className="flex flex-col gap-3">
            <p className="text-2xs text-fg-subtle">{`${d.project} vs ${report.from} → ${report.to}`}</p>
            <ul aria-label="change counts" className="flex flex-wrap gap-1.5">
                {CHANGES.map((c) => (
                    <li key={c}>
                        <Pill tone={CHANGE_TONE[c]}>{`${CHANGE_MARK[c]} ${n[c]} ${c}`}</Pill>
                    </li>
                ))}
            </ul>
            {none && <p className="text-xs text-fg-subtle">No graph changes.</p>}
            <Section title="changed" count={d.changed.length}>
                {d.changed.map((x) => (
                    <Def
                        key={`${x.path}:${x.name}`}
                        d={x}
                        change="changed"
                        extra={
                            <Pill tone="warn">{x.signature_changed ? "signature" : "body"}</Pill>
                        }
                    />
                ))}
            </Section>
            <Section title="removed" count={d.removed.length}>
                {d.removed.map((x) => (
                    <Def key={`${x.path}:${x.name}`} d={x} change="removed" />
                ))}
            </Section>
            <Moves title="renamed" rows={d.renamed} change="changed" />
            <Moves title="moved" rows={d.moved} change="moved" />
            <Section title="added" count={d.added.length}>
                {d.added.map((x) => (
                    <Def key={`${x.path}:${x.name}`} d={x} change="added" />
                ))}
            </Section>
            {(["added", "removed"] as const).map((c) => {
                const rows = c === "added" ? d.edges_added : d.edges_removed;
                return (
                    <Section key={c} title={`edges ${c}`} count={rows.length}>
                        {rows.map((e) => (
                            <Row key={`${e.path}:${e.scope}:${e.name}`}>
                                <Pill tone={CHANGE_TONE[c]}>{CHANGE_MARK[c]}</Pill>
                                <span className="truncate">{`${e.scope || e.path} → ${e.name}`}</span>
                            </Row>
                        ))}
                    </Section>
                );
            })}
            {(["added", "removed"] as const).map((c) => {
                const rows = c === "added" ? report.links_added : report.links_removed;
                return (
                    <Section key={c} title={`links ${c}`} count={rows.length}>
                        {rows.map((l) => (
                            <Row key={`${l.from}>${l.to}`}>
                                <Pill tone={CHANGE_TONE[c]}>{CHANGE_MARK[c]}</Pill>
                                <span className="truncate">{`${l.from} → ${l.to} (${l.kind})`}</span>
                            </Row>
                        ))}
                    </Section>
                );
            })}
            <Section title="changed, not analysed" count={d.not_analysed.length}>
                {d.not_analysed.map((u) => (
                    <Row key={u.path}>
                        <Pill tone="warn">{u.reason}</Pill>
                        <span className="truncate">{u.path}</span>
                    </Row>
                ))}
            </Section>
            {(d.more ?? 0) > 0 && (
                <Result kind="warn">{`${d.more} more rows are not shown; run \`rtok graph diff\` for all of them.`}</Result>
            )}
            {report.notes && (
                <p className="text-2xs whitespace-pre-line text-fg-subtle">{report.notes.trim()}</p>
            )}
        </div>
    );
}

/** The side panel of Compare mode: where the old side comes from, then the changes the server found. */
export function ComparePanel({ c, name }: { c: Compare; name: string }) {
    const { data, error, isFetching } = c.q;
    const d = projectOf(data, name);
    return (
        <section aria-label="compare" className="flex flex-col gap-2">
            <Search
                label="compare with"
                placeholder="HEAD, a ref or PROJECT:REF, then Enter"
                value={c.typed}
                onChange={c.setTyped}
                onEnter={c.send}
            />
            <div className="flex flex-wrap items-center gap-2 text-xs">
                <label className="flex items-center gap-1.5 text-2xs font-semibold text-fg-muted">
                    saved export
                    <input
                        type="file"
                        accept="application/json,.json"
                        onChange={(e) => void c.pick(e.target.files?.[0])}
                        className={`${focusRing} text-2xs`}
                    />
                </label>
                {c.saved && (
                    <>
                        <Pill tone="info">{c.saved.name}</Pill>
                        <Button onClick={() => c.setSaved(null)}>Use a ref</Button>
                    </>
                )}
            </div>
            {c.refused && <Result kind="error">{c.refused}</Result>}
            {error && (
                <Result verb="diff" kind="error">
                    {error.message}
                </Result>
            )}
            {isFetching && (
                <p role="status" aria-busy className="text-xs text-fg-muted">
                    Comparing…
                </p>
            )}
            {data && d && <Changes report={data} d={d} />}
            {data && !d && (
                <p className="text-xs text-fg-subtle">Nothing to compare for this project.</p>
            )}
        </section>
    );
}
