// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";
import { LiveStatusView } from "./LiveStatus";

const meta = {
    title: "Shell/Live status",
    component: LiveStatusView,
    args: { link: "open", updatedAt: 7_000, now: 10_000, paused: false, onPausedChange: fn() },
    // The strip sits in the shell header, a flex row.
    decorators: [(Story) => <div className="flex items-center gap-3 p-3">{Story()}</div>],
} satisfies Meta<typeof LiveStatusView>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Live: Story = {
    play: async ({ canvasElement, args }) => {
        const c = within(canvasElement);
        await expect(c.getByText("updated 3s ago")).toBeVisible();
        await userEvent.click(c.getByRole("button", { name: "Pause live updates" }));
        await expect(args.onPausedChange).toHaveBeenCalledWith(true);
    },
};

export const Paused: Story = {
    args: { paused: true },
    play: async ({ canvasElement, args }) => {
        const c = within(canvasElement);
        await expect(c.getByText("paused")).toBeVisible();
        await expect(c.getByText("Live updates paused")).toBeInTheDocument();
        await userEvent.click(c.getByRole("button", { name: "Pause live updates" }));
        await expect(args.onPausedChange).toHaveBeenCalledWith(false);
    },
};

export const WaitingForFirstSnapshot: Story = {
    args: { updatedAt: undefined, link: "connecting" },
};
