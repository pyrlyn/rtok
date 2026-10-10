// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useMutation } from "@tanstack/react-query";
import { useId, useState } from "react";
import { type Imported, useGraphFileApi } from "../../api/query";
import type { ExportRequest, ProjectRow } from "../../api/snapshot.gen";
import { Button } from "../../ui/Button";
import { focusRing } from "../../ui/cx";
import { Result } from "../../ui/Result";
import { Select } from "../../ui/Select";
import { download } from "../../ui/tableExport";
import { tooLarge } from "./ComparePanel";
import type { DrillState } from "./drillState";

type Scope = "overview" | "project" | "focus";
type Format = ExportRequest["format"];

const decode = (data: string) => Uint8Array.from(atob(data), (c) => c.charCodeAt(0));

/** The request for one choice of the menu; the server draws and redacts, as `rtok graph export` does. */
export function exportRequest(
    scope: Scope,
    project: string,
    drill: DrillState | null,
    image: { format: Format; scale: number; transparent: boolean },
): ExportRequest {
    const focus = scope === "focus" ? (drill?.focus?.name ?? null) : null;
    return {
        project,
        level: scope === "overview" ? "overview" : "symbols",
        focus,
        depth: focus ? (drill?.depth ?? null) : null,
        format: image.format,
        scale: image.format === "png" ? image.scale : null,
        transparent: image.format !== "json" && image.transparent,
    };
}

/**
 * The Export menu of the graph page (T329 §8c) and the file picker of the read-only import view.
 * The server makes the file, the same function behind `rtok graph export`; the page only saves it.
 * The panel says what a file contains before anything is sent.
 */
export function ExportMenu({
    rows,
    drill,
    onImported,
}: {
    rows: ProjectRow[];
    drill: DrillState | null;
    onImported(view: Imported): void;
}) {
    const api = useGraphFileApi();
    const [open, setOpen] = useState(false);
    const [scope, setScope] = useState<Scope>("overview");
    const [image, setImage] = useState({ format: "json" as Format, scale: 1, transparent: false });
    const [refused, setRefused] = useState<string | null>(null);
    const panel = useId();
    const save = useMutation({
        mutationFn: (request: ExportRequest) => api.exportGraph(request),
        onSuccess: (file) => download(file.name, file.mime, decode(file.data)),
    });
    const load = useMutation({ mutationFn: api.importGraph, onSuccess: onImported });

    // A drill-down names its project; the overview exports the selected project's scope, as the CLI does.
    const row =
        rows.find((r) => String(r.id) === drill?.project) ??
        rows.find((r) => r.selected) ??
        rows[0];
    const choices: { id: Scope; label: string }[] = [
        { id: "overview", label: `Overview of ${row?.name}: its projects and links` },
        ...(drill ? [{ id: "project" as const, label: `Symbol graph of ${row?.name}` }] : []),
        ...(drill?.focus
            ? [
                  {
                      id: "focus" as const,
                      label: `Subgraph around ${drill.focus.name}, ${drill.depth} ${drill.depth === 1 ? "call" : "calls"} deep`,
                  },
              ]
            : []),
    ];
    const active = choices.some((c) => c.id === scope) ? scope : "overview";
    const pick = async (file: File | undefined) => {
        if (!file) return;
        const refusal = tooLarge(file);
        setRefused(refusal);
        if (!refusal) load.mutate({ name: file.name, text: await file.text() });
    };

    return (
        <section aria-label="export" className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-3">
                <Button
                    disabled={!row}
                    aria-expanded={open}
                    aria-controls={panel}
                    onClick={() => setOpen(!open)}
                >
                    Export
                </Button>
                <label className="flex items-center gap-1.5 text-2xs font-semibold text-fg-muted">
                    open an export
                    <input
                        type="file"
                        accept="application/json,.json"
                        onChange={(e) => {
                            pick(e.target.files?.[0]);
                            e.target.value = "";
                        }}
                        className={`${focusRing} text-2xs`}
                    />
                </label>
            </div>
            {refused && <Result kind="error">{refused}</Result>}
            {load.error && (
                <Result verb="open" kind="error">
                    {load.error.message}
                </Result>
            )}
            {open && row && (
                <div
                    id={panel}
                    role="group"
                    aria-label="export options"
                    className="flex flex-col gap-2 rounded-md border border-border p-2.5 text-xs"
                >
                    <p>
                        File names and symbol names are included in the export; source text never
                        is. Your home directory, your user name and absolute paths are replaced.
                    </p>
                    <fieldset className="flex flex-col gap-1">
                        <legend className="text-2xs font-semibold text-fg-muted">
                            what to export
                        </legend>
                        {choices.map((c) => (
                            <label key={c.id} className="flex items-center gap-1.5">
                                <input
                                    type="radio"
                                    name={`${panel}-scope`}
                                    checked={c.id === active}
                                    onChange={() => setScope(c.id)}
                                />
                                {c.label}
                            </label>
                        ))}
                    </fieldset>
                    <div className="flex flex-wrap items-center gap-2">
                        <Select
                            label="format"
                            value={image.format}
                            onChange={(v) => setImage({ ...image, format: v as Format })}
                        >
                            <option value="json">JSON (all of it)</option>
                            <option value="svg">SVG picture</option>
                            <option value="png">PNG picture</option>
                        </Select>
                        {image.format === "png" && (
                            <Select
                                label="size"
                                value={String(image.scale)}
                                onChange={(v) => setImage({ ...image, scale: Number(v) })}
                            >
                                <option value="1">1x</option>
                                <option value="2">2x</option>
                                <option value="4">4x</option>
                            </Select>
                        )}
                        {image.format !== "json" && (
                            <label className="flex items-center gap-1.5">
                                <input
                                    type="checkbox"
                                    checked={image.transparent}
                                    onChange={(e) =>
                                        setImage({ ...image, transparent: e.target.checked })
                                    }
                                />
                                transparent background
                            </label>
                        )}
                        <Button
                            variant="solid"
                            verb="download"
                            pending={save.isPending}
                            onClick={() =>
                                save.mutate(exportRequest(active, String(row.id), drill, image))
                            }
                        >
                            Download
                        </Button>
                    </div>
                    {image.format !== "json" && (
                        <p className="text-2xs text-fg-subtle">
                            A picture shows the 200 best connected nodes; the JSON file has all of
                            them.
                        </p>
                    )}
                    {save.error && (
                        <Result verb="export" kind="error">
                            {save.error.message}
                        </Result>
                    )}
                </div>
            )}
        </section>
    );
}
