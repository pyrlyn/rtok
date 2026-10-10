// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { expect, waitFor, within } from "storybook/test";
import { healthRows } from "./graph3d/healthFixtures";
import { HealthBreakdown, HealthRing } from "./graph3d/HealthRing";
import { ProjectsOverview, VIEW_KEY } from "./graph3d/ProjectsOverview";
import { Projects } from "./Projects";
import { richSnapshot } from "./fixtures";
import { serve, withData } from "./storyData";

const meta = {
    title: "Pages/Graph health",
    component: ProjectsOverview,
    decorators: [withData(serve(richSnapshot))],
} satisfies Meta<typeof ProjectsOverview>;
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

/** The lazy scene chunks and the layout worker outlast the default 1 s wait on a busy host. */
const READY = { timeout: 10_000 };

/** A ring and its breakdown for each project: 100, 60 with two reasons, first index, missing. */
export const List: Story = {
    args: { rows: healthRows() },
    decorators: [viewing("list")],
    play: async ({ canvasElement }) => {
        const list = within(
            await within(canvasElement).findByRole("list", { name: "project graph" }),
        );
        await expect(list.getByLabelText("health 100")).toBeVisible();
        await expect(list.getByLabelText("health 60")).toBeVisible();
        await expect(list.getByLabelText("health indexing")).toBeVisible();
        await expect(list.getByLabelText("health missing")).toBeVisible();
        await expect(list.getByText(/3 files pending \(30%\)/)).toBeVisible();
        await expect(list.getAllByText(/fix: /)).toHaveLength(4);
    },
};

export const ListLight: Story = { ...List, globals: { theme: "light" } };

export const TwoD: Story = {
    args: { rows: healthRows() },
    decorators: [viewing("2d")],
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getAllByTestId("health-2d")).toHaveLength(4), READY);
        await expect(
            canvas.getByRole("button", { name: /lagging, stale, health 60/ }),
        ).toBeVisible();
    },
};

export const TwoDLight: Story = { ...TwoD, globals: { theme: "light" } };

/** The selected project with its ring, breakdown and the scope's lowest score. */
export const Selected: StoryObj<typeof Projects> = {
    render: () => <Projects rows={healthRows()} />,
    play: async ({ canvasElement }) => {
        const current = within(await within(canvasElement).findByLabelText("current project"));
        await expect(current.getByText("scope 60")).toBeVisible();
        await expect(current.getByLabelText("health 100")).toBeVisible();
    },
};

export const Parts: StoryObj = {
    render: () => (
        <div className="flex flex-wrap gap-4">
            {healthRows().map((p) => (
                <div key={p.id} className="flex items-start gap-2">
                    <HealthRing health={p.health} size={32} />
                    <HealthBreakdown health={p.health} />
                </div>
            ))}
        </div>
    ),
};
