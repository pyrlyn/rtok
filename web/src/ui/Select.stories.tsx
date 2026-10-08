// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent, within } from "storybook/test";
import { Select } from "./Select";

const options = [
    <option key="" value="">
        link to…
    </option>,
    <option key="ketch" value="ketch">
        ketch
    </option>,
    <option key="rtok" value="rtok">
        rtok
    </option>,
];

const meta = {
    component: Select,
    args: { label: "link to", value: "", onChange: () => {}, children: options },
} satisfies Meta<typeof Select>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const OnLight: Story = { globals: { theme: "light" } };

export const Picks: Story = {
    render: (args) => {
        const [value, setValue] = useState("");
        return <Select {...args} value={value} onChange={setValue} />;
    },
    play: async ({ canvasElement }) => {
        const select = within(canvasElement).getByRole("combobox", { name: "link to" });
        await userEvent.selectOptions(select, "ketch");
        await expect(select).toHaveValue("ketch");
    },
};
