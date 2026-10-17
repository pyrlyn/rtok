// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, within } from "storybook/test";
import { drillServer } from "../api/sampleDrill";
import { project } from "../api/sampleRows";
import type { ProjectRow, Snapshot } from "../api/snapshot.gen";
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
];
const snapshot: Snapshot = { ...richSnapshot, projects: registry };

/** A function in focus, so all three choices of the menu are offered. */
const FOCUSED = "/?p=1&fp=src%2Fhook.rs&fn=run_hook&d=2";

const meta = {
    title: "Pages/Graph export",
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

const READY = { timeout: 10_000 };

export const MenuOpen: Story = {
    decorators: [viewing("list"), withData(drillServer(snapshot), FOCUSED)],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.click(await canvas.findByRole("button", { name: "Export" }, READY));
        const options = within(canvas.getByRole("group", { name: "export options" }));
        await expect(options.getByText(/File names and symbol names are included/)).toBeVisible();
        await expect(
            options.getByRole("radio", { name: /Subgraph around run_hook/ }),
        ).toBeVisible();
        await userEvent.selectOptions(options.getByLabelText("format"), "png");
        await expect(options.getByLabelText("size")).toBeVisible();
    },
};

export const MenuOpenLight: Story = { ...MenuOpen, globals: { theme: "light" } };

const SAVED = JSON.stringify({
    schema: "rtok.graph.v1",
    projects: [
        { id: 1, name: "rtok", root: "~/rtok", origin: "manual", backend: "tags", health: "ok" },
        {
            id: 2,
            name: "ketch",
            root: "~/ketch",
            origin: "manual",
            backend: "tags",
            health: "stale",
        },
    ],
    links: [{ from: 1, to: 2, kind: "manual", references: 3 }],
    nodes: [
        {
            id: "1:src/hook.rs:10:run_hook",
            project: 1,
            kind: "function",
            name: "run_hook",
            path: "src/hook.rs",
            line: 10,
        },
    ],
    edges: [],
    meta: {
        scope: ["rtok", "ketch"],
        level: "symbols",
        exported_at: 1_700_000_000,
        rtok_version: "0.15.1",
        redacted: true,
        partial: true,
        notes: ["ketch: text search only, no call edges"],
    },
});

export const OpenedExport: Story = {
    decorators: [viewing("list"), withData(drillServer(snapshot), "/")],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.upload(
            await canvas.findByLabelText("open an export", undefined, READY),
            new File([SAVED], "shared.json", { type: "application/json" }),
        );
        await expect(
            await canvas.findByText("viewing export from shared.json", undefined, READY),
        ).toBeVisible();
        await expect(canvas.getByText("rtok → ketch")).toBeVisible();
        await expect(canvas.getByRole("region", { name: "symbols" })).toBeVisible();
        await userEvent.click(canvas.getByRole("button", { name: "Close export" }));
        await expect(canvas.queryByText(/viewing export from/)).toBeNull();
    },
};

export const OpenedExportLight: Story = { ...OpenedExport, globals: { theme: "light" } };

export const RefusedExport: Story = {
    decorators: [viewing("list"), withData(drillServer(snapshot), "/")],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.upload(
            await canvas.findByLabelText("open an export", undefined, READY),
            new File(["not json"], "notes.json"),
        );
        await expect(
            await canvas.findByText(/notes\.json is not a graph export/, undefined, READY),
        ).toBeVisible();
    },
};
