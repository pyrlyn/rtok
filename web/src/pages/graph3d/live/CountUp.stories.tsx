// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { useEffect, useState } from "react";
import { expect, waitFor, within } from "storybook/test";
import { Kpi } from "../../../ui/Kpi";
import { CountUp } from "./CountUp";

const meta = {
    title: "Pages/Live graph/CountUp",
    component: CountUp,
    args: { value: 12 },
    decorators: [
        (Story) => (
            <div className="w-56">
                <Kpi label="calls" value={<Story />} />
            </div>
        ),
    ],
} satisfies Meta<typeof CountUp>;
export default meta;
type Story = StoryObj<typeof meta>;

export const AtValue: Story = {};

/** The number counts up on the first frames and lands on the server's total. */
export const CountsUp: Story = {
    render: function CountsUpStory(args) {
        const [value, setValue] = useState(0);
        useEffect(() => {
            const t = setTimeout(() => setValue(args.value ?? 12), 16);
            return () => clearTimeout(t);
        }, [args.value]);
        return <CountUp {...args} value={value} />;
    },
    play: async ({ canvasElement }) => {
        const card = within(canvasElement);
        // The server's total is readable as soon as the frame lands; the picture catches up.
        await waitFor(() => expect(card.getByText("12")).toBeTruthy());
        await waitFor(() => {
            const spans = [...canvasElement.querySelectorAll("span")];
            const landed = spans.some(
                (s) => s.textContent === "12" && !s.hasAttribute("aria-hidden"),
            );
            const counting = spans.some(
                (s) => s.getAttribute("aria-hidden") === "true" && s.textContent !== "12",
            );
            expect(landed || counting).toBe(true);
        });
    },
};

export const CountsUpLight: Story = { ...CountsUp, globals: { theme: "light" } };
