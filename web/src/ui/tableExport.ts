// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { RowData } from "@tanstack/react-table";
import { sortRows, type Cell, type Column, type Sort } from "./DataTable";

export interface ExportTable {
  ids: string[];
  headers: string[];
  rows: Cell[][];
}

/** The rows as `DataTable` shows them (same order), with only the columns that have a plain value. */
export function exportTable<T extends RowData>(
  columns: readonly Column<T>[],
  rows: readonly T[],
  sort?: Sort,
): ExportTable {
  const picked = columns.flatMap((c) => {
    const value = c.exportValue ?? c.sortValue;
    return value ? [{ c, value }] : [];
  });
  return {
    ids: picked.map((p) => p.c.id),
    headers: picked.map((p) => p.c.header),
    rows: sortRows(rows, columns, sort).map((row) => picked.map((p) => p.value(row))),
  };
}

// https://owasp.org/www-community/attacks/CSV_Injection (checked 2026-10-08): a cell starting with
// = + - @ tab CR LF, or a full-width variant of those four, can run as a formula in a spreadsheet.
const FORMULA_LEAD = /^[=+\-@\t\r\n＝＋－＠]/;

/** Numbers are ours and cannot be formulas, so a negative one stays a number; only text is escaped. */
function csvField(cell: Cell): string {
  if (cell == null) return "";
  if (typeof cell !== "string") return String(cell);
  const guarded = FORMULA_LEAD.test(cell);
  const text = guarded ? `'${cell}` : cell;
  return guarded || /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

/** RFC 4180 (CRLF lines, doubled quotes); an empty table is still its header row. */
export function toCsv({ headers, rows }: ExportTable): string {
  if (headers.length === 0) return "";
  return [headers, ...rows].map((r) => `${r.map(csvField).join(",")}\r\n`).join("");
}

/** An array of objects keyed by column id; missing values become `null`. */
export function toJson({ ids, rows }: ExportTable): string {
  return JSON.stringify(
    rows.map((r) => Object.fromEntries(ids.map((id, i) => [id, r[i] ?? null]))),
    null,
    2,
  );
}

export type ExportFormat = "csv" | "json";

export const MIME: Record<ExportFormat, string> = {
  csv: "text/csv;charset=utf-8",
  json: "application/json",
};

export function serialize(table: ExportTable, format: ExportFormat): string {
  return format === "csv" ? toCsv(table) : toJson(table);
}

/** Saves text as a file through a throwaway object URL; nothing leaves the browser. */
export function download(filename: string, mime: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  // Revoked on a later tick: some browsers start the save after click() returns.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
