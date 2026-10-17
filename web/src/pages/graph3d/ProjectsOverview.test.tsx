// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { project } from "../../api/sampleRows";
import type { ProjectRow, Snapshot } from "../../api/snapshot.gen";
import { richSnapshot } from "../fixtures";
import { mount, serving, wire } from "../testHelpers";
import { VIEW_KEY } from "./ProjectsOverview";

beforeEach(() => localStorage.clear());
afterEach(cleanup);

const rows: ProjectRow[] = [
    project(1, "rtok", {
        selected: true,
        links: [{ to: 2, kind: "manual", name: "ketch", reason: null }],
    }),
    project(2, "ketch", {
        links: [{ to: 3, kind: "auto", name: "pyrlyn", reason: "shared deps" }],
    }),
    project(3, "pyrlyn", { state: "failed" }),
];
const page = (projects: ProjectRow[] = rows) => {
    const snap: Snapshot = { ...richSnapshot, projects };
    return mount(serving(snap), "/graph");
};
const view = (name: string) => screen.getByRole("button", { name });

describe("graph overview", () => {
    test("without WebGL the 3D choice falls back to 2D with a notice", async () => {
        page();
        expect((await screen.findByText(/3D view unavailable/)).textContent).toMatch(
            /3D view unavailable/,
        );
        expect(await screen.findAllByTestId("node-2d")).toHaveLength(3);
        expect(screen.queryByTestId("graph-3d")).toBeNull();
    });

    test("the view choice survives a remount", async () => {
        const first = page();
        await screen.findByText(/3D view unavailable/);
        fireEvent.click(view("List"));
        expect(localStorage.getItem(VIEW_KEY)).toBe("list");
        first.unmount();
        page();
        const list = await screen.findByRole("list", { name: "project graph" });
        expect(within(list).getAllByRole("button")).toHaveLength(3);
        expect(screen.queryByTestId("node-2d")).toBeNull();
    });

    test("an unknown stored view falls back to the default", async () => {
        localStorage.setItem(VIEW_KEY, "nonsense");
        page();
        expect(await screen.findByText(/3D view unavailable/)).toBeTruthy();
    });

    test("the list names every link and its kind", async () => {
        localStorage.setItem(VIEW_KEY, "list");
        page();
        const list = await screen.findByRole("list", { name: "project graph" });
        expect(list.textContent).toMatch(/ketch \(manual\)/);
        expect(list.textContent).toMatch(/pyrlyn \(auto\)/);
    });

    test("the filter narrows the graph and the counters show the whole registry", async () => {
        localStorage.setItem(VIEW_KEY, "list");
        page();
        fireEvent.change(await screen.findByLabelText("filter projects"), {
            target: { value: "ket" },
        });
        const list = screen.getByRole("list", { name: "project graph" });
        expect(within(list).getAllByRole("button")).toHaveLength(1);
        expect(screen.getByText("linked pairs").parentElement?.textContent).toMatch(/2/);
    });

    test("a missing project is drawn but cannot be selected", async () => {
        localStorage.setItem(VIEW_KEY, "list");
        page([
            project(1, "a", { selected: true }),
            project(2, "b", { state: "missing", missing: true }),
        ]);
        const list = await screen.findByRole("list", { name: "project graph" });
        const b = within(list).getAllByRole("button")[1] as HTMLButtonElement;
        expect(b.disabled).toBe(true);
    });

    test("one project explains how to link another", async () => {
        page([project(1, "solo", { selected: true })]);
        expect(await screen.findByText(/graph projects link/)).toBeTruthy();
    });

    test("no registry means no overview", async () => {
        page([]);
        await screen.findByText("No projects");
        expect(screen.queryByRole("group", { name: "view" })).toBeNull();
    });
});

describe("re-index and remove from the node menu (T329.54)", () => {
    const mounted = (projects: ProjectRow[] = rows) => {
        localStorage.setItem(VIEW_KEY, "2d");
        const w = wire({ ...richSnapshot, projects });
        mount(w.connect, "/graph");
        return w;
    };
    const menuOf = async (name: string) => {
        const nodes = await screen.findAllByTestId("node-2d");
        fireEvent.contextMenu(nodes.find((n) => n.getAttribute("aria-label")!.startsWith(name))!);
    };
    const asked = (w: ReturnType<typeof wire>) =>
        w.sent.filter((m) => "project" in m).map((m) => ("project" in m ? m.project : m));

    test("Re-index sends the request, and not for a missing or indexing project", async () => {
        const w = mounted([
            project(1, "rtok", { selected: true }),
            project(2, "gone", { state: "missing", missing: true }),
            project(3, "busy", { state: "indexing" }),
        ]);
        await menuOf("rtok");
        fireEvent.click(await screen.findByRole("menuitem", { name: "Re-index" }));
        await waitFor(() => expect(asked(w)).toEqual([{ action: "reindex", project: "1" }]));

        for (const name of ["gone", "busy"]) {
            await menuOf(name);
            const item = await screen.findByRole("menuitem", { name: "Re-index" });
            expect(item.hasAttribute("disabled")).toBe(true);
            fireEvent.keyDown(document, { key: "Escape" });
        }
        expect(asked(w)).toHaveLength(1);
    });

    test("Remove asks for the plan, and only Confirm sends the removal", async () => {
        const w = mounted();
        await menuOf("ketch");
        fireEvent.click(await screen.findByRole("menuitem", { name: "Remove…" }));
        expect(asked(w)).toEqual([{ action: "remove", project: "2", confirmed: false }]);

        const region = await screen.findByRole("region", { name: "remove ketch" });
        const confirm = within(region).getByRole("button", { name: /Remove ketch/ });
        expect(confirm.hasAttribute("disabled")).toBe(true);
        act(() =>
            w.answer({
                type: "projectplan",
                project: "2",
                plan: "would remove /x/ketch\n  files untouched; nothing removed (dry run)\n",
            }),
        );
        expect((await within(region).findByText(/files untouched/)).textContent).toMatch(
            /nothing removed/,
        );
        expect(asked(w)).toHaveLength(1);
        fireEvent.click(confirm);
        await waitFor(() =>
            expect(asked(w)[1]).toEqual({ action: "remove", project: "2", confirmed: true }),
        );
        expect(screen.queryByRole("region", { name: "remove ketch" })).toBeNull();
    });

    test("declining the plan sends nothing more", async () => {
        const w = mounted();
        await menuOf("rtok");
        fireEvent.click(await screen.findByRole("menuitem", { name: "Remove…" }));
        const region = await screen.findByRole("region", { name: "remove rtok" });
        act(() => w.answer({ type: "projectplan", project: "1", plan: "would remove /x/rtok\n" }));
        fireEvent.click(within(region).getByRole("button", { name: "Cancel" }));
        expect(screen.queryByRole("region", { name: "remove rtok" })).toBeNull();
        expect(asked(w)).toEqual([{ action: "remove", project: "1", confirmed: false }]);
    });

    test("the page shows an indexing project and a failed one with its error", async () => {
        mounted([
            project(1, "rtok", { selected: true, state: "indexing" }),
            project(2, "ketch", { state: "failed", error: "disk full" }),
        ]);
        expect((await screen.findByText("Indexing rtok…")).getAttribute("role")).toBe("status");
        expect((await screen.findByText(/ketch: disk full/)).closest("[role=alert]")).toBeTruthy();
    });
});
