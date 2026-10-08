// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent, within } from "storybook/test";
import { Button } from "./Button";
import { Result } from "./Result";

const meta = {
    component: Button,
    args: { verb: "expand", children: "expand 3f9a" },
} satisfies Meta<typeof Button>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Idle: Story = {};
export const Solid: Story = { args: { variant: "solid" } };
export const Pending: Story = {
    args: { pending: true },
    play: async ({ canvasElement }) => {
        const button = within(canvasElement).getByRole("button");
        await expect(button).toBeDisabled();
        await expect(button).toHaveAttribute("aria-busy", "true");
    },
};
export const SolidPending: Story = { args: { variant: "solid", pending: true } };
export const Disabled: Story = { args: { disabled: true } };
export const NoOperation: Story = { args: { verb: undefined, children: "Cancel" } };

// idle -> pending -> done | error, the way a page drives it from a mutation.
function Action({ fail }: { fail: boolean }) {
    const [phase, setPhase] = useState<"idle" | "pending" | "done" | "error">("idle");
    const run = () => {
        setPhase("pending");
        setTimeout(() => setPhase(fail ? "error" : "done"), 150);
    };
    return (
        <div className="flex flex-col gap-2">
            <Button verb="expand" pending={phase === "pending"} onClick={run}>
                expand 3f9a
            </Button>
            {phase === "done" && (
                <Result verb="expand" kind="success">
                    120 lines
                </Result>
            )}
            {phase === "error" && (
                <Result verb="expand" kind="error">
                    expand 3f9a timed out
                </Result>
            )}
        </div>
    );
}

export const SpinsUntilDone: Story = {
    render: () => <Action fail={false} />,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.click(canvas.getByRole("button"));
        await expect(canvas.getByRole("button")).toHaveAttribute("aria-busy", "true");
        await expect(await canvas.findByText("120 lines")).toBeVisible();
        await expect(canvas.getByRole("button")).not.toHaveAttribute("aria-busy");
        await expect(canvas.getByRole("button")).toBeEnabled();
    },
};

export const SpinsUntilError: Story = {
    render: () => <Action fail />,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await userEvent.click(canvas.getByRole("button"));
        await expect(await canvas.findByRole("alert")).toHaveTextContent("timed out");
        await expect(canvas.getByRole("button")).toBeEnabled();
    },
};
