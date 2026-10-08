// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { Result } from "./Result";

const meta = {
    component: Result,
    args: { kind: "success", verb: "doctor", children: "3 entries removed" },
} satisfies Meta<typeof Result>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Done: Story = {};
export const Failed: Story = {
    args: { kind: "error", verb: "link", children: "project 9 is gone" },
};
export const Warning: Story = {
    args: { kind: "warn", verb: "doctor", children: "1 entry was skipped" },
};
// A verb that names no operation takes the icon of its kind.
export const KindFallback: Story = { args: { kind: "info", verb: "select", children: "ketch" } };
