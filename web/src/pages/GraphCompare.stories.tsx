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

/** drill.rs and hook.rs open, so the changed, added and removed symbols are all drawn. */
const AT = `/?p=1&x=${encodeURIComponent(JSON.stringify(["src/plugins/graph/drill.rs", "src/hook.rs"]))}`;

const meta = {
    title: "Pages/Graph compare",
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

export const ChangesOnTheGraph: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot), AT)],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.click(await canvas.findByRole("button", { name: "Compare" }, READY));
        // The mark in the label and the word in the name say what the colour says.
        await expect(
            await canvas.findByRole("button", { name: /^~ run, function · changed/ }, READY),
        ).toBeVisible();
        await expect(
            canvas.getByRole("button", { name: /^\+ resolve, function · added/ }),
        ).toBeVisible();
        await expect(
            canvas.getByRole("button", { name: /^− legacy_hook, function · removed/ }),
        ).toBeVisible();
        const side = within(canvas.getByRole("region", { name: "compare" }));
        await expect(side.getByText("~ 2 changed")).toBeVisible();
        await expect(side.getByText("→ 1 moved")).toBeVisible();
        await expect(side.getByRole("region", { name: "changed, not analysed" })).toBeVisible();
    },
};

export const ChangesOnTheGraphLight: Story = { ...ChangesOnTheGraph, globals: { theme: "light" } };

export const ListWithChangeWords: Story = {
    decorators: [viewing("list"), withData(drillServer(snapshot), AT)],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.click(await canvas.findByRole("button", { name: "Compare" }, READY));
        const list = within(await canvas.findByRole("list", { name: "symbol graph" }, READY));
        await expect(
            await list.findByRole("button", { name: /^run.*changed/ }, READY),
        ).toBeVisible();
        await expect(list.getByRole("button", { name: /^legacy_hook.*removed/ })).toBeVisible();
    },
};

export const AgainstASavedExport: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot), AT)],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.click(await canvas.findByRole("button", { name: "Compare" }, READY));
        const file = new File(['{"schema":"rtok.graph.v1"}'], "before.json", {
            type: "application/json",
        });
        await userEvent.upload(await canvas.findByLabelText("saved export"), file);
        const side = within(canvas.getByRole("region", { name: "compare" }));
        await expect(
            await side.findByText(/only added and removed symbols/, undefined, READY),
        ).toBeVisible();
        await expect(side.getByRole("region", { name: "links added" })).toBeVisible();
        await expect(side.queryByRole("region", { name: "changed" })).toBeNull();
    },
};

export const RefusedFile: Story = {
    decorators: [viewing("2d"), withData(drillServer(snapshot), AT)],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.click(await canvas.findByRole("button", { name: "Compare" }, READY));
        await userEvent.upload(
            await canvas.findByLabelText("saved export"),
            new File(["not json"], "notes.json"),
        );
        await expect(
            await canvas.findByText(/notes\.json is not a graph export/, undefined, READY),
        ).toBeVisible();
    },
};
