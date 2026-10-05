// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { Chart } from "./Chart";
import type { ChartSpec } from "./spec";

const x = ["17:13", "17:14", "17:15", "17:16", "17:17", "17:18"];
const calls: ChartSpec = {
    kind: "stacked-bars",
    axes: true,
    sync: "story",
    label: "calls per minute",
    x,
    series: [
        { id: "hook", label: "hook", values: [1, 3, 0, 2, 4, 1], tone: "accent" },
        { id: "mcp", label: "mcp", values: [0, 1, 2, 0, 1, 0], tone: "accent-soft" },
        { id: "proxy", label: "proxy", values: [1, 0, 0, 1, 0, 2], tone: "muted" },
    ],
    dots: { id: "failed", label: "failed", values: [0, 1, 0, 0, 0, 0], tone: "delta" },
};
const live: ChartSpec = {
    kind: "line",
    sync: "story",
    label: "live sessions per minute",
    x,
    series: [{ id: "live", label: "live", values: [1, 2, 2, 3, 2, 2], tone: "accent" }],
};

const meta = {
    component: Chart,
    args: { spec: calls, className: "h-44 w-full" },
} satisfies Meta<typeof Chart>;
export default meta;
type Story = StoryObj<typeof meta>;

// The canvas loads lazily; the first draw is the sign the renderer mounted.
const drawn = (root: HTMLElement) =>
    waitFor(() => expect(root.querySelector("canvas")).not.toBeNull(), { timeout: 10_000 });

export const StackedBars: Story = {
    play: async ({ canvasElement }) => {
        const chart = within(canvasElement).getByRole("img", { name: "calls per minute" });
        await drawn(chart);
        chart.focus();
        await userEvent.keyboard("{End}");
        const tip = await within(document.body).findByRole("tooltip");
        await expect(tip).toHaveTextContent("17:18");
        await expect(tip).toHaveTextContent("proxy2");
        await expect(chart).toHaveAttribute("aria-describedby", tip.id);
        await userEvent.keyboard("{ArrowLeft}{ArrowLeft}{ArrowLeft}{ArrowLeft}");
        await expect(tip).toHaveTextContent("17:14");
        await expect(tip).toHaveTextContent("failed1");
        await userEvent.keyboard("{Escape}");
        await waitFor(() => expect(within(document.body).queryByRole("tooltip")).toBeNull());
    },
};

export const Mini: Story = { args: { spec: { ...live, sync: undefined }, className: "h-7 w-48" } };

/** Two charts on one time axis: the hovered one shows the tooltip, the other only its pointer. */
export const Synced: Story = {
    render: () => (
        <div className="flex w-[560px] flex-col gap-3">
            <Chart spec={calls} className="h-44 w-full" />
            <Chart spec={live} className="h-7 w-full" />
        </div>
    ),
    play: async ({ canvasElement }) => {
        const c = within(canvasElement);
        const mini = c.getByRole("img", { name: "live sessions per minute" });
        await drawn(c.getByRole("img", { name: "calls per minute" }));
        await drawn(mini);
        mini.focus();
        await userEvent.keyboard("{Home}");
        const tips = await within(document.body).findAllByRole("tooltip");
        await expect(tips).toHaveLength(1);
        await expect(tips[0]).toHaveTextContent("live1");
    },
};
