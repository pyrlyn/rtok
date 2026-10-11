// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { Panel } from "../../../ui/Panel";
import { HIDE_KEY, MAX_KEY, Split } from "./Split";

const meta = {
    title: "Pages/Graph split",
    component: Split,
    args: {
        explorer: (
            <Panel title="project graph">
                <p>explorer body</p>
            </Panel>
        ),
        live: (
            <Panel title="live graph">
                <p>live body</p>
            </Panel>
        ),
    },
    beforeEach: () => {
        try {
            localStorage.removeItem(MAX_KEY);
            localStorage.removeItem(HIDE_KEY);
        } catch {
            // Storage blocked: the default (no maximise) applies.
        }
    },
} satisfies Meta<typeof Split>;
export default meta;
type Story = StoryObj<typeof meta>;

/** The two parts sit side by side, each with its maximise button. */
export const SideBySide: Story = {
    play: async ({ canvasElement }) => {
        const view = within(canvasElement);
        expect(view.getByRole("button", { name: "Maximise explorer" })).toBeTruthy();
        expect(view.getByRole("button", { name: "Maximise live graph" })).toBeTruthy();
        expect(view.getByRole("separator", { name: "resize the live graph" })).toBeTruthy();
        expect(view.getByText("explorer body")).toBeTruthy();
        expect(view.getByText("live body")).toBeTruthy();
    },
};

/** Maximising the explorer fills the area and restores to the split. */
export const MaximiseExplorer: Story = {
    play: async ({ canvasElement }) => {
        const view = within(canvasElement);
        await userEvent.click(view.getByRole("button", { name: "Maximise explorer" }));
        await expect(view.getByRole("button", { name: "Restore explorer" })).toHaveAttribute(
            "aria-pressed",
            "true",
        );
        expect(view.getByText("explorer body")).toBeTruthy();
        expect(view.queryByText("live body")).toBeNull();
        expect(view.queryByRole("separator")).toBeNull();

        await userEvent.click(view.getByRole("button", { name: "Restore explorer" }));
        await waitFor(() => expect(view.getByText("live body")).toBeTruthy());
        expect(view.getByRole("separator")).toBeTruthy();
    },
};

/** Maximising the live graph fills the area; Escape restores the split. */
export const MaximiseLiveAndEscape: Story = {
    play: async ({ canvasElement }) => {
        const view = within(canvasElement);
        await userEvent.click(view.getByRole("button", { name: "Maximise live graph" }));
        await expect(view.getByRole("button", { name: "Restore live graph" })).toHaveAttribute(
            "aria-pressed",
            "true",
        );
        expect(view.getByText("live body")).toBeTruthy();
        expect(view.queryByText("explorer body")).toBeNull();
        expect(view.queryByRole("separator")).toBeNull();

        await userEvent.keyboard("{Escape}");
        await waitFor(() => expect(view.getByText("explorer body")).toBeTruthy());
        expect(view.getByRole("separator")).toBeTruthy();
        expect(view.getByRole("button", { name: "Maximise live graph" })).toBeTruthy();
    },
};

export const Light: Story = { ...SideBySide, globals: { theme: "light" } };
