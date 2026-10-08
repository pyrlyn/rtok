// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { richSnapshot } from "./fixtures";
import { callBuckets, SURFACES } from "./model";
import { Overview } from "./Overview";
import { serve, withData } from "./storyData";

const meta = {
    title: "Pages/Overview linked hover",
    component: Overview,
    decorators: [withData(serve(richSnapshot))],
} satisfies Meta<typeof Overview>;
export default meta;
type Story = StoryObj<typeof meta>;

const drawn = (el: HTMLElement) =>
    waitFor(() => expect(el.querySelector("canvas")).not.toBeNull(), { timeout: 10_000 });
const tips = () => within(document.body).queryAllByRole("tooltip");

/** Hovering the calls chart: the minis draw the pointer, the KPIs print the bucket, one tooltip. */
export const HoverCallsChart: Story = {
    play: async ({ canvasElement }) => {
        const c = within(canvasElement);
        const big = await c.findByRole("img", { name: /^Calls over time/ });
        const calls = c.getByRole("img", { name: "calls per bucket" });
        const live = c.getByRole("img", { name: "sessions alive over the calls window" });
        for (const chart of [big, calls, live]) await drawn(chart);
        await expect(c.queryAllByTestId("hover-readout")).toHaveLength(0);

        big.focus();
        await userEvent.keyboard("{Home}");
        await waitFor(() => expect(calls).toHaveAttribute("data-pointer", "0"));
        await expect(live).toHaveAttribute("data-pointer", "0");
        const readouts = await c.findAllByTestId("hover-readout");
        await expect(readouts).toHaveLength(2);
        await expect(readouts[0]).toHaveTextContent(/\d\d:\d\d:\d\d · \d+ calls/);
        await expect(readouts[1]).toHaveTextContent(/\d\d:\d\d:\d\d · \d+ live/);
        // The tooltip is the only one on the page, and it belongs to the hovered chart.
        await expect(tips()).toHaveLength(1);
        await expect(big).toHaveAttribute("aria-describedby", tips()[0]!.id);
        // The swapped subline stays readable to assistive tech.
        await expect(c.getByText(/failed · p95/)).toBeInTheDocument();

        await userEvent.keyboard("{Escape}");
        await waitFor(() => expect(c.queryAllByTestId("hover-readout")).toHaveLength(0));
        await expect(calls).not.toHaveAttribute("data-pointer");
    },
};

/** Hovering a KPI mini: its own card keeps the subline (the tooltip has the value), the other prints it. */
export const HoverMini: Story = {
    play: async ({ canvasElement }) => {
        const c = within(canvasElement);
        const calls = await c.findByRole("img", { name: "calls per bucket" });
        const big = c.getByRole("img", { name: /^Calls over time/ });
        await drawn(calls);
        await drawn(big);
        calls.focus();
        await userEvent.keyboard("{End}");
        await waitFor(() => expect(big).toHaveAttribute("data-pointer", "23"));
        const readouts = await c.findAllByTestId("hover-readout");
        await expect(readouts).toHaveLength(1);
        await expect(readouts[0]).toHaveTextContent("live");
        await expect(tips()).toHaveLength(1);
        await expect(calls.closest(".glass")).toHaveTextContent(/failed · p95/);
    },
};

/** Marks share the chart tooltip: the plugin bitset, the token mix and the budget grid. */
export const MarkTooltips: Story = {
    play: async ({ canvasElement }) => {
        const c = within(canvasElement);
        const bits = await c.findByRole("img", { name: /plugins enabled/ });
        await userEvent.hover(bits.children[2]!);
        await expect(await within(document.body).findByRole("tooltip")).toHaveTextContent(
            /graph.*disabled/,
        );
        await userEvent.unhover(bits.children[2]!);
        await waitFor(() => expect(tips()).toHaveLength(0));

        const grid = c.getByRole("img", { name: /of the estimated tokens cut/ });
        await userEvent.hover(grid);
        await expect(await within(document.body).findByRole("tooltip")).toHaveTextContent(
            /cut.*of 16 cells/,
        );
        await userEvent.unhover(grid);

        const mix = c.getByRole("img", { name: /^input \d+%/ });
        await userEvent.hover(mix.children[0]!);
        await expect(await within(document.body).findByRole("tooltip")).toHaveTextContent(
            /input.*share/,
        );
    },
};

/** The pointer on a stacked segment lights that series in the calls legend, and only there. */
export const HoverLightsLegend: Story = {
    play: async ({ canvasElement }) => {
        const c = within(canvasElement);
        const big = await c.findByRole("img", { name: /^Calls over time/ });
        await drawn(big);
        // A bucket holding one call sits in the bottom quarter of the plot (scale 4, grid padding
        // 8 above and 22 below, 32 left and 8 right), so its middle is the middle of that column.
        const { buckets } = callBuckets(richSnapshot.calls);
        const at = buckets.findIndex((b) => b.hook + b.mcp + b.proxy === 1);
        const surface = SURFACES.find((s) => buckets[at]![s] === 1)!;
        const r = big.getBoundingClientRect();
        const plot = r.height - 30;
        const target = big.querySelector("canvas")!;
        const clientX = r.left + 32 + ((at + 0.5) / buckets.length) * (r.width - 40);
        const clientY = r.bottom - 22 - plot / 8;
        for (const type of ["mouseover", "mousemove"])
            target.dispatchEvent(new MouseEvent(type, { clientX, clientY, bubbles: true }));
        const legend = await c.findByText(new RegExp(`^${surface} \\d+$`));
        await waitFor(() => expect(legend.closest("li")).toHaveAttribute("data-lit"));
        for (const other of SURFACES.filter((s) => s !== surface))
            await expect(
                c.getByText(new RegExp(`^${other} \\d+$`)).closest("li"),
            ).not.toHaveAttribute("data-lit");
    },
};
