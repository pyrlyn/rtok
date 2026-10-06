// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { richSnapshot } from "../pages/fixtures";
import { Palette, ShortcutHelp, type Target } from "./Palette";
import { useShortcuts } from "./shortcuts";

// What the Shell does, minus the router: the last jump is printed so the play can read it.
function Harness() {
    const [open, setOpen] = useState(false);
    const [help, setHelp] = useState(false);
    const [dark, setDark] = useState(true);
    const [last, setLast] = useState<Target>();
    useShortcuts({
        palette: () => setOpen((o) => !o),
        help: () => setHelp(true),
        go: (page) => setLast({ page }),
    });
    return (
        <>
            <output aria-label="last jump">
                {last ? `${last.page}:${last.id ?? ""}` : "none"}
            </output>
            <Palette
                isOpen={open}
                onOpenChange={setOpen}
                snap={richSnapshot}
                dark={dark}
                onGo={setLast}
                onTheme={() => setDark((d) => !d)}
            />
            <ShortcutHelp isOpen={help} onOpenChange={setHelp} />
        </>
    );
}

const meta = { component: Harness } satisfies Meta<typeof Harness>;
export default meta;
type Story = StoryObj<typeof meta>;

// The palette portals to <body>, outside the story root.
const page = () => within(document.body);

export const JumpToPlugin: Story = {
    play: async ({ canvasElement }) => {
        const root = within(canvasElement);
        await userEvent.keyboard("{Control>}k{/Control}");
        const search = await page().findByRole("searchbox", { name: /search pages/i });
        await waitFor(() => expect(search).toHaveFocus());
        await userEvent.type(search, "graph");
        // The page and the plugin match; the plugin is second.
        await userEvent.keyboard("{ArrowDown}{Enter}");
        await waitFor(() =>
            expect(root.getByLabelText("last jump")).toHaveTextContent("plugins:graph"),
        );
        await waitFor(() => expect(page().queryByRole("dialog")).toBeNull());
    },
};

export const GoAndHelp: Story = {
    play: async ({ canvasElement }) => {
        const root = within(canvasElement);
        await userEvent.keyboard("gs");
        await expect(root.getByLabelText("last jump")).toHaveTextContent("sessions:");
        await userEvent.keyboard("?");
        const help = await page().findByRole("dialog", { name: "Keyboard shortcuts" });
        await expect(within(help).getByText("worktrees")).toBeVisible();
        await userEvent.keyboard("{Escape}");
        await waitFor(() => expect(page().queryByRole("dialog")).toBeNull());
    },
};
