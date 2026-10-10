// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { alertRow, project } from "../../api/sampleRows";
import type { ProjectRow, Snapshot } from "../../api/snapshot.gen";
import { richSnapshot } from "../fixtures";
import { mount, wire } from "../testHelpers";

beforeEach(() => localStorage.clear());
afterEach(cleanup);

const gone = alertRow("missing", "ketch", "the directory no longer exists");
const rows = (by: Record<string, ProjectRow["alerts"]> = {}): ProjectRow[] => {
    return [
        project(1, "rtok", {
            selected: true,
            links: [{ to: 2, kind: "manual", name: "ketch", reason: null }],
        }),
        project(2, "ketch", { alerts: by.ketch ?? [] }),
        project(3, "pyrlyn", { alerts: by.pyrlyn ?? [] }),
    ];
};
const snap = (projects: ProjectRow[]): Snapshot => ({ ...richSnapshot, projects });
const toasts = () => screen.queryByRole("list", { name: "notifications" });

describe("graph alerts", () => {
    test("alerts already up when the page opens are listed, badged and not toasted", async () => {
        mount(wire(snap(rows({ ketch: [gone] }))).connect, "/graph");
        const list = await screen.findByRole("list", { name: "alerts" });
        expect(list.textContent).toMatch(/ketch missing since \d\d:\d\d/);
        expect(await screen.findAllByTestId("alert-2d")).toHaveLength(1);
        expect(within(toasts()!).queryAllByRole("listitem")).toHaveLength(0);
    });

    test("an alert raised by a later snapshot is a toast, and its recovery another", async () => {
        const w = wire(snap(rows()));
        mount(w.connect, "/graph");
        await screen.findAllByTestId("node-2d");
        expect(screen.queryByRole("list", { name: "alerts" })).toBeNull();

        act(() => w.push(snap(rows({ ketch: [gone] }))));
        const raised = await within(toasts()!).findByText(/ketch missing since/);
        expect(raised).toBeTruthy();

        act(() => w.push(snap(rows())));
        expect(await within(toasts()!).findByText("ketch missing: recovered")).toBeTruthy();
        expect(screen.queryByRole("list", { name: "alerts" })).toBeNull();
        expect(screen.queryAllByTestId("alert-2d")).toHaveLength(0);
    });

    test("projects hit by the same problem make one toast and one list line", async () => {
        const w = wire(snap(rows()));
        mount(w.connect, "/graph");
        await screen.findAllByTestId("node-2d");
        const down = (name: string) => alertRow("unreachable", name, "share did not answer");
        act(() => w.push(snap(rows({ ketch: [down("ketch")], pyrlyn: [down("pyrlyn")] }))));
        const line = await within(toasts()!).findByText(
            /ketch, pyrlyn unreachable .*\(2 projects\)/,
        );
        expect(line).toBeTruthy();
        const list = screen.getByRole("list", { name: "alerts" });
        expect(within(list).getAllByRole("listitem")).toHaveLength(1);
        expect(screen.getAllByTestId("alert-2d")).toHaveLength(2);
    });

    test("a toast can be dismissed", async () => {
        const w = wire(snap(rows()));
        mount(w.connect, "/graph");
        await screen.findAllByTestId("node-2d");
        act(() => w.push(snap(rows({ ketch: [gone] }))));
        fireEvent.click(await within(toasts()!).findByRole("button", { name: "dismiss" }));
        expect(within(toasts()!).queryAllByRole("listitem")).toHaveLength(0);
    });

    test("the list view marks an alerted project in words", async () => {
        localStorage.setItem("rtok.graph.view", "list");
        mount(wire(snap(rows({ ketch: [gone] }))).connect, "/graph");
        const list = await screen.findByRole("list", { name: "project graph" });
        expect(list.textContent).toMatch(/ketch.*alert/);
    });
});
