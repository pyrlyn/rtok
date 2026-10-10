// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { useEffect, useState } from "react";
import { expect, within } from "storybook/test";
import { alertRow, project } from "../api/sampleRows";
import type { ProjectRow } from "../api/snapshot.gen";
import { GraphAlerts } from "./graph3d/GraphAlerts";

const meta = {
    title: "Pages/Graph alerts",
    component: GraphAlerts,
} satisfies Meta<typeof GraphAlerts>;
export default meta;
type Story = StoryObj<typeof meta>;

const gone = alertRow("missing", "docs-site", "the directory no longer exists");
const share = (n: number) => alertRow("unreachable", `share-${n}`, "/Volumes/share did not answer");

const registry = (alerted: boolean): ProjectRow[] => [
    project(1, "rtok", { selected: true }),
    project(2, "docs-site", { missing: true, state: "missing", alerts: alerted ? [gone] : [] }),
    ...[1, 2, 3].map((n) => project(n + 2, `share-${n}`, { alerts: alerted ? [share(n)] : [] })),
];

export const MissingAndGrouped: Story = {
    args: { rows: registry(true) },
    play: async ({ canvasElement }) => {
        const list = within(await within(canvasElement).findByRole("list", { name: "alerts" }));
        // Four alerts, two lines: the three unreachable projects read as one.
        await expect(list.getAllByRole("listitem")).toHaveLength(2);
        await expect(list.getByText(/share-1, share-2, share-3 unreachable/)).toBeVisible();
    },
};

export const Light: Story = { args: { rows: registry(true) }, globals: { theme: "light" } };
export const Quiet: Story = { args: { rows: registry(false) } };

/** The alerts clear a moment after the page opened: the list goes away and a toast says so. */
export const Recovered: Story = {
    args: { rows: registry(true) },
    render: ({ rows }) => {
        const [now, setNow] = useState(rows);
        useEffect(() => {
            const t = setTimeout(() => setNow(registry(false)), 50);
            return () => clearTimeout(t);
        }, []);
        return <GraphAlerts rows={now} />;
    },
    play: async ({ canvasElement }) => {
        const toasts = within(
            await within(canvasElement).findByRole("list", { name: "notifications" }),
        );
        await expect(await toasts.findByText("docs-site missing: recovered")).toBeVisible();
        await expect(
            await toasts.findByText("share-1, share-2, share-3 unreachable: recovered"),
        ).toBeVisible();
    },
};
