// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useNavigate, useSearch } from "@tanstack/react-router";
import { useCallback, useEffect, useRef, useState } from "react";
import { LEVELS } from "./pages/model";
import type { Sort } from "./ui/DataTable";

/**
 * Filters, search text and sort of the list pages live in the route's search params (T414.10):
 * a link restores the view and back/forward step through it. One spec per page; every page
 * reads it through `useTableSearch`, so no page keeps its own copy of this logic.
 */
export interface TableSpec {
  /** Each filter's allowed values; the first is the default and never reaches the URL. */
  filters: Record<string, readonly string[]>;
  /** Column ids a header click may sort by. */
  sort: readonly string[];
  /** Whether the page has a text box. */
  text: boolean;
}

export const SURFACES = ["all", "hook", "mcp", "proxy"] as const;
export const RESULTS = ["any", "ok", "failed"] as const;
export const SHOW = ["all", "on", "off", "saves"] as const;
export const SESSION_SHOW = ["all", "live"] as const;

export const TABLE_SPECS = {
  calls: {
    text: true,
    filters: { surface: SURFACES, result: RESULTS },
    sort: ["time", "surface", "name", "ms", "tok", "ok"],
  },
  sessions: {
    text: true,
    filters: { show: SESSION_SHOW },
    sort: ["status", "id", "tok", "last"],
  },
  plugins: {
    text: true,
    filters: { show: SHOW },
    sort: ["on", "plugin", "rows", "saved"],
  },
  // `line` is the gutter number; ascending is the order the snapshot carries.
  logs: { text: true, filters: { level: LEVELS }, sort: ["line"] },
} as const satisfies Record<string, TableSpec>;

export type TablePage = keyof typeof TABLE_SPECS;

type Allowed<V> = V extends readonly (infer U)[] ? U : never;

export type TableState<S extends TableSpec> = {
  q: string;
  sort: Sort | undefined;
  filter: { [K in keyof S["filters"]]: Allowed<S["filters"][K]> };
};

// The hash router's default parser turns "123" and "true" into numbers and booleans, so a value
// that looks like one arrives as one; anything else that is not a string is junk.
const asString = (v: unknown): string | undefined =>
  typeof v === "string"
    ? v
    : typeof v === "number" || typeof v === "boolean"
      ? String(v)
      : undefined;

const MAX_TEXT = 200;

export function parseSort(raw: unknown, allowed: readonly string[]): Sort | undefined {
  const s = asString(raw);
  if (!s) return undefined;
  const desc = s.startsWith("-");
  const id = desc ? s.slice(1) : s;
  return allowed.includes(id) ? { id, desc } : undefined;
}

export const formatSort = (sort: Sort | undefined): string | undefined =>
  sort && `${sort.desc ? "-" : ""}${sort.id}`;

/** Untrusted search params in, a complete state out: whatever does not validate falls back to the default. */
export function parseTableSearch<S extends TableSpec>(
  spec: S,
  raw: Record<string, unknown>,
): TableState<S> {
  const filter: Record<string, string> = {};
  for (const [key, values] of Object.entries(spec.filters)) {
    const v = asString(raw[key]);
    filter[key] = v !== undefined && values.includes(v) ? v : (values[0] ?? "");
  }
  return {
    q: spec.text ? (asString(raw.q) ?? "").slice(0, MAX_TEXT) : "",
    sort: parseSort(raw.sort, spec.sort),
    filter: filter as TableState<S>["filter"],
  };
}

/** The state as search params; defaults are left out (`undefined`) so a plain view has a plain URL. */
export function toSearch<S extends TableSpec>(
  spec: S,
  state: TableState<S>,
): Record<string, string | undefined> {
  const out: Record<string, string | undefined> = {
    q: state.q || undefined,
    sort: formatSort(state.sort),
  };
  for (const [key, values] of Object.entries(spec.filters)) {
    const v = (state.filter as Record<string, string>)[key];
    out[key] = v === values[0] ? undefined : v;
  }
  return out;
}

/** For a route's `validateSearch`: the table's own params, normalised, plus whatever else the page takes. */
export const validateTableSearch = (page: string, raw: Record<string, unknown>) => {
  const spec = (TABLE_SPECS as Record<string, TableSpec>)[page];
  return spec ? toSearch(spec, parseTableSearch(spec, raw)) : {};
};

export function useTableSearch<P extends TablePage>(page: P) {
  type S = (typeof TABLE_SPECS)[P];
  const spec: TableSpec = TABLE_SPECS[page];
  const raw = useSearch({ strict: false }) as Record<string, unknown>;
  const navigate = useNavigate();
  const state = parseTableSearch(spec, raw) as TableState<S>;

  const write = useCallback(
    (patch: Partial<TableState<S>>, replace: boolean) =>
      void navigate({
        to: ".",
        replace,
        // `prev` carries the other params (`id`), which a table change must not drop.
        search: ((prev: Record<string, unknown>) => ({
          ...prev,
          ...toSearch(spec, { ...parseTableSearch(spec, prev), ...patch } as TableState<TableSpec>),
        })) as never,
      }),
    [navigate, spec],
  );

  // The box keeps its own text: a router round trip per keystroke would move the caret. The
  // values written but not yet seen in the URL are remembered, so a late echo of "a" does not
  // overwrite the "ab" typed since; a URL value nobody here wrote (back, forward) does.
  const [text, setText] = useState(state.q);
  const pending = useRef<string[]>([]);
  useEffect(() => {
    const at = pending.current.indexOf(state.q);
    if (at >= 0) pending.current.splice(0, at + 1);
    else {
      pending.current = [];
      setText(state.q);
    }
  }, [state.q]);

  return {
    ...state,
    q: text,
    /** Typing replaces the history entry, so back skips the keystrokes. */
    setQ: (q: string) => {
      const next = q.slice(0, MAX_TEXT);
      pending.current.push(next);
      setText(next);
      write({ q: next }, true);
    },
    setFilter: <K extends keyof S["filters"]>(key: K, value: Allowed<S["filters"][K]>) =>
      write({ filter: { ...state.filter, [key]: value } } as Partial<TableState<S>>, false),
    setSort: (sort: Sort | undefined) => write({ sort }, false),
  };
}

/**
 * A link to a list page with some filters set and everything else at its default, built from the
 * page's own spec so a link and the page that reads it cannot disagree on a param name or value.
 */
export function tableLink<P extends TablePage>(
  page: P,
  filter: Partial<TableState<(typeof TABLE_SPECS)[P]>["filter"]>,
) {
  const spec: TableSpec = TABLE_SPECS[page];
  const base = parseTableSearch(spec, {});
  return {
    to: `/${page}` as const,
    search: toSearch(spec, { ...base, filter: { ...base.filter, ...filter } }),
  };
}
