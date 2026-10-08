// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, test } from "vitest";
import { DataProvider } from "./api/query";
import { richSnapshot } from "./pages/fixtures";
import { serving } from "./pages/testHelpers";
import { createAppRouter } from "./router";
import {
    formatSort,
    parseSort,
    parseTableSearch,
    TABLE_SPECS,
    toSearch,
    validateTableSearch,
} from "./tableSearch";
import { nextSort, sortRows } from "./ui/DataTable";

afterEach(cleanup);

const calls = TABLE_SPECS.calls;

describe("table search params", () => {
    test("a plain URL parses to the defaults", () => {
        expect(parseTableSearch(calls, {})).toEqual({
            q: "",
            sort: undefined,
            filter: { surface: "all", result: "any" },
        });
    });

    test("valid values are kept and defaults are left out when written back", () => {
        const state = parseTableSearch(calls, {
            q: "read",
            surface: "mcp",
            result: "any",
            sort: "-ms",
        });
        expect(state).toEqual({
            q: "read",
            sort: { id: "ms", desc: true },
            filter: { surface: "mcp", result: "any" },
        });
        expect(toSearch(calls, state)).toEqual({
            q: "read",
            sort: "-ms",
            surface: "mcp",
            result: undefined,
        });
    });

    test("bad values fall back to the default instead of failing", () => {
        const raw = {
            surface: "carrier-pigeon",
            result: ["ok"],
            sort: "-bogus",
            q: { $ne: 1 },
        };
        expect(parseTableSearch(calls, raw)).toEqual(parseTableSearch(calls, {}));
        expect(parseSort("--ms", calls.sort)).toBeUndefined();
        expect(parseSort("__proto__", calls.sort)).toBeUndefined();
    });

    test("text that the router read as a number or boolean comes back as text", () => {
        expect(parseTableSearch(calls, { q: 404 }).q).toBe("404");
        expect(parseTableSearch(calls, { q: true }).q).toBe("true");
    });

    test("an absurdly long query is cut", () => {
        expect(parseTableSearch(calls, { q: "x".repeat(10_000) }).q).toHaveLength(200);
    });

    test("a filter of another page is not read, and pages without a table add nothing", () => {
        expect(parseTableSearch(TABLE_SPECS.sessions, { surface: "mcp" }).filter).toEqual({
            show: "all",
        });
        expect(validateTableSearch("doctor", { q: "x", sort: "ms" })).toEqual({});
        expect(validateTableSearch("calls", { result: "nope", sort: "ms" })).toMatchObject({
            sort: "ms",
            result: undefined,
        });
    });

    test("sort ids round trip", () => {
        expect(formatSort(parseSort("tok", calls.sort))).toBe("tok");
        expect(formatSort(parseSort("-tok", calls.sort))).toBe("-tok");
        expect(formatSort(undefined)).toBeUndefined();
    });

    test("a header click goes ascending, descending, off", () => {
        const first = nextSort(undefined, "ms");
        expect(first).toEqual({ id: "ms", desc: false });
        const second = nextSort(first, "ms");
        expect(second).toEqual({ id: "ms", desc: true });
        expect(nextSort(second, "ms")).toBeUndefined();
        expect(nextSort(second, "tok")).toEqual({ id: "tok", desc: false });
    });

    test("rows sort stably with missing values last in both directions", () => {
        const rows = [{ n: 2 }, { n: null }, { n: 1 }, { n: 2 }, { n: undefined }];
        const columns = [
            {
                id: "n",
                header: "n",
                cell: () => null,
                sortValue: (r: (typeof rows)[number]) => r.n,
            },
        ];
        expect(sortRows(rows, columns, { id: "n", desc: false }).map((r) => r.n)).toEqual([
            1,
            2,
            2,
            null,
            undefined,
        ]);
        expect(sortRows(rows, columns, { id: "n", desc: true }).map((r) => r.n)).toEqual([
            2,
            2,
            1,
            null,
            undefined,
        ]);
        expect(sortRows(rows, columns, { id: "gone", desc: true })).toEqual(rows);
    });
});

function open(path: string) {
    const history = createMemoryHistory({ initialEntries: [path] });
    const router = createAppRouter(history);
    render(
        <DataProvider connect={serving(richSnapshot)}>
            <RouterProvider router={router} />
        </DataProvider>,
    );
    return { history, router };
}

const names = (table: string) =>
    within(screen.getByRole("table", { name: table }))
        .getAllByRole("row")
        .slice(1)
        .map((r) => r.textContent ?? "");

describe("the list pages read the URL", () => {
    test("a filtered link opens the filtered rows", async () => {
        open("/calls?result=failed");
        await screen.findByRole("table", { name: "calls" });
        expect(names("calls")).toHaveLength(1);
        expect(names("calls")[0]).toContain("PreToolUse");
        expect(screen.getByRole("button", { name: "failed" }).getAttribute("aria-pressed")).toBe(
            "true",
        );
    });

    test("a link with junk params shows the unfiltered page", async () => {
        const { router } = open("/calls?result=zzz&surface=%00&sort=-nope");
        await screen.findByRole("table", { name: "calls" });
        expect(names("calls")).toHaveLength(richSnapshot.calls.length);
        await waitFor(() => expect(router.state.location.search).toEqual({}));
    });

    test("sort comes from the URL, a header click writes it, and back undoes it", async () => {
        const { history, router } = open("/calls?sort=-ms");
        await screen.findByRole("table", { name: "calls" });
        expect(names("calls")[0]).toContain("PreToolUse");
        const msHeader = screen.getByRole("columnheader", { name: /ms/ });
        expect(msHeader.getAttribute("aria-sort")).toBe("descending");

        fireEvent.click(within(msHeader).getByRole("button"));
        await waitFor(() => expect(router.state.location.search).toEqual({}));
        expect(msHeader.getAttribute("aria-sort")).toBe("none");

        act(() => history.back());
        await waitFor(() => expect(msHeader.getAttribute("aria-sort")).toBe("descending"));
    });

    test("a chip pushes an entry, typing replaces one, and both survive back and forward", async () => {
        const { history, router } = open("/sessions");
        await screen.findByRole("table", { name: "sessions" });
        const all = names("sessions").length;

        fireEvent.click(screen.getByRole("switch", { name: "live only" }));
        await waitFor(() => expect(router.state.location.search).toEqual({ show: "live" }));
        const live = names("sessions").length;
        expect(live).toBeLessThan(all);

        const box = screen.getByRole("searchbox", { name: "Filter sessions" });
        fireEvent.change(box, { target: { value: "n" } });
        fireEvent.change(box, { target: { value: "no-such-session" } });
        await waitFor(() =>
            expect(router.state.location.search).toEqual({ show: "live", q: "no-such-session" }),
        );
        expect((box as HTMLInputElement).value).toBe("no-such-session");

        // One back undoes the whole query, because typing replaced the entry instead of pushing.
        act(() => history.back());
        await waitFor(() => expect(router.state.location.search).toEqual({}));
        expect((box as HTMLInputElement).value).toBe("");
        expect(names("sessions")).toHaveLength(all);

        act(() => history.forward());
        await waitFor(() =>
            expect(router.state.location.search).toEqual({ show: "live", q: "no-such-session" }),
        );
        expect((box as HTMLInputElement).value).toBe("no-such-session");
    });

    test("plugins and logs filter from the URL too, and keep the palette's id", async () => {
        open("/plugins?show=off&id=graph");
        await screen.findByRole("table", { name: "plugins" });
        expect(names("plugins")).toHaveLength(1);
        cleanup();

        open("/logs?level=error");
        const lines = await screen.findByRole("list", { name: "log lines" });
        expect(lines.children.length).toBeGreaterThan(0);
        for (const li of Array.from(lines.children)) expect(li.textContent).toMatch(/ERROR/i);
    });
});
