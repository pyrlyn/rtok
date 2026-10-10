// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { BIG, drillServer } from "../api/sampleDrill";
import { project } from "../api/sampleRows";
import type { ProjectRow, Snapshot } from "../api/snapshot.gen";
import { drawn } from "./canvasPixels";
import { richSnapshot } from "./fixtures";
import { Graph } from "./Graph";
import { VIEW_KEY } from "./graph3d/ProjectsOverview";
import { withData } from "./storyData";

const registry: ProjectRow[] = [
    project(1, "rtok", {
        selected: true,
        links: [{ to: 2, kind: "manual", name: "ketch", reason: null }],
    }),
    project(2, "ketch"),
    project(3, BIG),
    project(4, "gone", { state: "missing", missing: true, index: null }),
    project(5, "fresh", { state: "stale", index: null }),
];
const snapshot = (projects = registry): Snapshot => ({ ...richSnapshot, projects });

/** The drill-down state rides the URL, so a story starts at a link. */
const at = (p: number, files: string[] = []) =>
    `/?p=${p}${files.length ? `&x=${encodeURIComponent(JSON.stringify(files))}` : ""}`;

const meta = {
    title: "Pages/Graph drill-down",
    component: Graph,
} satisfies Meta<typeof Graph>;
export default meta;
type Story = StoryObj<typeof meta>;

const viewing =
    (view: string): Decorator =>
    (Story) => {
        try {
            localStorage.setItem(VIEW_KEY, view);
        } catch {
            // Storage blocked: the default (3D) applies.
        }
        return <Story />;
    };

/** The lazy chunks and the layout worker outlast the default 1 s wait on a busy host. */
const READY = { timeout: 10_000 };
const nodeNamed = (c: ReturnType<typeof within>, name: RegExp) =>
    c.findByRole("button", { name }, READY);

export const Files: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot()), at(1))],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await nodeNamed(canvas, /^drill\.rs, file/);
        await expect(await canvas.findAllByTestId("node-2d")).toHaveLength(5);
        await expect(canvas.getByRole("navigation", { name: "breadcrumb" })).toBeVisible();
    },
};

export const FilesLight: Story = { ...Files, globals: { theme: "light" } };

export const OpenExpandAndFocus: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot()), "/")],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        // Level 1: a double-click on the project opens it.
        const rtok = (await canvas.findAllByTestId("node-2d", undefined, READY)).find((n) =>
            n.getAttribute("aria-label")!.startsWith("rtok"),
        )!;
        await userEvent.dblClick(rtok);
        // A second click on a file expands it.
        const file = await nodeNamed(canvas, /^drill\.rs, file/);
        await userEvent.click(file);
        await userEvent.click(file);
        await userEvent.click(await nodeNamed(canvas, /^run, function/));
        await userEvent.click(await nodeNamed(canvas, /^run, function/));
        await expect(await canvas.findByRole("group", { name: "depth" })).toBeVisible();
        const crumbs = within(canvas.getByRole("navigation", { name: "breadcrumb" }));
        await expect(crumbs.getByText("src/plugins/graph/drill.rs")).toBeVisible();
    },
};

export const ExternalNodeOpensTheLinkedProject: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot()), at(1, ["src/store.rs"]))],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.click(await nodeNamed(canvas, /^open_index, external/));
        const crumbs = within(await canvas.findByRole("navigation", { name: "breadcrumb" }));
        await expect(await crumbs.findByText("src/lib.rs", undefined, READY)).toBeVisible();
        await expect(crumbs.getByText("ketch")).toBeVisible();
    },
};

export const MoreOnALargeProject: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot()), at(3))],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        const more = await canvas.findByRole("button", { name: "+100 more" }, READY);
        await waitFor(() => expect(canvas.getAllByTestId("node-2d").length).toBeGreaterThan(400), READY);
        await userEvent.click(more);
        await waitFor(() => expect(canvas.queryByText("+100 more")).toBeNull(), READY);
    },
};

export const ListView: Story = {
    decorators: [viewing("list"), withData(drillServer(snapshot()), at(1, ["src/store.rs"]))],
    play: async ({ canvasElement }) => {
        const list = await within(canvasElement).findByRole("list", { name: "symbol graph" }, READY);
        await expect(within(list).getAllByRole("button").length).toBeGreaterThan(4);
    },
};

export const NotIndexed: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot()), at(5))],
    play: async ({ canvasElement }) =>
        expect(await within(canvasElement).findByText("Not indexed yet", undefined, READY)).toBeVisible(),
};

export const MissingDirectory: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot()), at(4))],
    play: async ({ canvasElement }) =>
        expect(
            await within(canvasElement).findByText("The directory is missing", undefined, READY),
        ).toBeVisible(),
};

export const PartialIndex: Story = {
    decorators: [
        viewing("2d"),
        withData(
            drillServer(
                snapshot([
                    project(1, "rtok", {
                        selected: true,
                        index: { files: 4, indexed_at: 1, pending: 2, rows: 90, watch: "on" },
                    }),
                ]),
            ),
            at(1),
        ),
    ],
    play: async ({ canvasElement }) =>
        expect(await within(canvasElement).findByText(/^Partial:/, undefined, READY)).toBeVisible(),
};

export const ThreeDDrawsTheShapes: Story = {
    decorators: [viewing("3d"), withData(drillServer(snapshot()), at(1, ["src/store.rs"]))],
    play: async ({ canvasElement }) => {
        const host = await within(canvasElement).findByTestId("graph-3d", undefined, READY);
        const gl = await waitFor(() => {
            const c = host.querySelector("canvas");
            if (!c) throw new Error("no canvas yet");
            return c;
        }, READY);
        await waitFor(() => expect(drawn(gl)).toBeGreaterThan(200), READY);
    },
};
