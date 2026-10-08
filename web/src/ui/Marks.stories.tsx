// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { Bitset, BudgetGrid, MiniBars } from "./Marks";

const meta = {
    title: "UI/Marks",
    component: MiniBars,
    args: { label: "calls per bucket", values: [3, 5, 4, 8, 6, 0, 7, 12, 10] },
} satisfies Meta<typeof MiniBars>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Bars: Story = {};
export const AllZero: Story = { args: { values: [0, 0, 0] } };
export const NoValues: Story = { args: { values: [] } };

const plugin = (id: string, enabled: boolean, saves_tokens = true) => ({
    id,
    enabled,
    saves_tokens,
});

// The grid sits on the mark tile, which stays dark in both themes.
export const Budget: StoryObj = {
    render: () => <BudgetGrid cut={0.62} label="62% of estimated tokens cut" />,
};
export const BudgetLight: StoryObj = { ...Budget, globals: { theme: "light" } };

export const PluginBitset: StoryObj = {
    render: () => (
        <Bitset
            items={[
                plugin("shell", true),
                plugin("read", true),
                plugin("graph", false),
                plugin("ledger", true, false),
                plugin("archive", true),
                plugin("budget", false),
                plugin("memory", true),
            ]}
        />
    ),
};
