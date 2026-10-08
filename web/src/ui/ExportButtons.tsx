// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { RowData } from "@tanstack/react-table";
import type { Column, Sort } from "./DataTable";
import { focusRing } from "./cx";
import { download, exportTable, MIME, serialize, type ExportFormat } from "./tableExport";

const FORMATS: readonly ExportFormat[] = ["csv", "json"];

/** Pass the same `rows`, `columns` and `sort` as the `DataTable` beside it, so the file is what is on screen. */
export function ExportButtons<T extends RowData>({
    label,
    rows,
    columns,
    sort,
}: {
    label: string;
    rows: readonly T[];
    columns: readonly Column<T>[];
    sort?: Sort;
}) {
    const slug = label.replaceAll(/\W+/g, "-");
    return (
        <div role="group" aria-label={`Export ${label}`} className="flex items-center gap-1.5">
            <span aria-hidden="true" className="text-fg-subtle">
                export
            </span>
            {FORMATS.map((format) => (
                <button
                    key={format}
                    type="button"
                    disabled={rows.length === 0}
                    aria-label={`Export ${label} as ${format.toUpperCase()}`}
                    onClick={() =>
                        download(
                            `rtok-${slug}.${format}`,
                            MIME[format],
                            serialize(exportTable(columns, rows, sort), format),
                        )
                    }
                    className={`${focusRing} h-6 cursor-pointer rounded-full border border-border px-2 font-semibold text-fg-muted uppercase hover:border-border-strong hover:text-fg disabled:cursor-not-allowed disabled:opacity-40`}
                >
                    {format}
                </button>
            ))}
        </div>
    );
}
