// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { sampleSnapshot } from "../api/sample";
import type { CallRow } from "../api/snapshot.gen";
import type { Column } from "./DataTable";
import { ExportButtons } from "./ExportButtons";

const columns: Column<CallRow>[] = [
    { id: "name", header: "name", sortValue: (c) => c.name, cell: (c) => c.name },
    { id: "ms", header: "ms", sortValue: (c) => c.ms, cell: (c) => c.ms },
];

const meta = {
    component: ExportButtons<CallRow>,
    args: { label: "calls", rows: sampleSnapshot.calls, columns },
} satisfies Meta<typeof ExportButtons<CallRow>>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
    play: async ({ canvasElement }) => {
        const group = within(canvasElement).getByRole("group", { name: "Export calls" });
        for (const name of ["Export calls as CSV", "Export calls as JSON"]) {
            await expect(within(group).getByRole("button", { name })).toBeEnabled();
        }
    },
};

export const NothingToExport: Story = {
    args: { rows: [] },
    play: async ({ canvasElement }) => {
        await expect(
            within(canvasElement).getByRole("button", { name: "Export calls as CSV" }),
        ).toBeDisabled();
    },
};
