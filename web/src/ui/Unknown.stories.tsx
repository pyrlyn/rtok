// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { Unknown } from "./Unknown";

const meta = {
    component: Unknown,
    args: { why: "The host did not report a model for this session." },
} satisfies Meta<typeof Unknown>;
export default meta;
type Story = StoryObj<typeof meta>;

export const FocusShowsTheReason: Story = {
    play: async ({ canvasElement, args }) => {
        const root = within(canvasElement);
        await expect(root.getByText("Unknown")).toBeVisible();
        await userEvent.tab();
        const button = root.getByRole("button", { name: "Why is this unknown?" });
        await waitFor(() => expect(button).toHaveFocus());
        // The tooltip portals to <body>.
        const tip = await within(document.body).findByRole("tooltip");
        await expect(tip).toHaveTextContent(args.why);
        await waitFor(() => expect(button).toHaveAttribute("aria-describedby", tip.id));
        await userEvent.keyboard("{Escape}");
        await waitFor(() => expect(within(document.body).queryByRole("tooltip")).toBeNull());
    },
};
