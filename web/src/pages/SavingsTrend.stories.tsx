// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { noSavingsDays, savingsDays } from "./savingsFixtures";
import { SavingsTrend } from "./SavingsTrend";

const meta = {
    title: "Pages/Savings trend",
    component: SavingsTrend,
    args: { days: savingsDays },
    decorators: [(Story) => <div className="w-[640px] max-w-full">{Story()}</div>],
} satisfies Meta<typeof SavingsTrend>;
export default meta;
type Story = StoryObj<typeof meta>;

export const SampleData: Story = {
    play: async ({ canvasElement }) => {
        const c = within(canvasElement);
        const chart = c.getByRole("img", { name: /Δtok saved per day/ });
        await waitFor(() => expect(chart.querySelector("canvas")).not.toBeNull(), {
            timeout: 10_000,
        });
        await expect(c.getByText(/2 other/)).toBeInTheDocument();
        chart.focus();
        await userEvent.keyboard("{End}");
        const tip = await within(document.body).findByRole("tooltip");
        await expect(tip).toHaveTextContent("2026-10-08 · 14 rows");
        await expect(tip).toHaveTextContent("shell4.1k");
        // A day without rows says so; it is never drawn or read as a 0.
        await userEvent.keyboard("{Home}{ArrowRight}{ArrowRight}");
        await expect(tip).toHaveTextContent("2026-09-27 · 0 rows");
        await expect(tip).toHaveTextContent("shellno data");
        await expect(tip).not.toHaveTextContent(/shell0\b/);
    },
};

export const SampleDataLight: Story = { globals: { theme: "light" } };

export const NoRows: Story = {
    args: { days: noSavingsDays },
    play: async ({ canvasElement }) => {
        const c = within(canvasElement);
        await expect(c.getByText("No measured savings in these days")).toBeInTheDocument();
        await expect(c.queryByRole("img")).toBeNull();
    },
};
