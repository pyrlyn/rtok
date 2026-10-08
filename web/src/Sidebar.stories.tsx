// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import {
    createMemoryHistory,
    createRootRoute,
    createRoute,
    createRouter,
    RouterProvider,
} from "@tanstack/react-router";
import { createContext, useContext, useState } from "react";
import { expect, userEvent, within } from "storybook/test";
import { PAGES } from "./pages";
import { NAV_GROUPS, Sidebar } from "./Sidebar";

// The sidebar's links need a router; every page renders nothing, only the nav is under test.
// The flag reaches the root route's component through context, so it stays one stable
// component and keeps focus across a toggle.
const Flag = createContext<{ collapsed: boolean; toggle: () => void }>({
    collapsed: false,
    toggle: () => {},
});

function Root() {
    const { collapsed, toggle } = useContext(Flag);
    return <Sidebar collapsed={collapsed} onToggle={toggle} />;
}

function Harness({ collapsed: initial }: { collapsed: boolean }) {
    const [collapsed, setCollapsed] = useState(initial);
    const [router] = useState(() => {
        const root = createRootRoute({ component: Root });
        const routes = PAGES.map((p) =>
            createRoute({ getParentRoute: () => root, path: `/${p.id}` }),
        );
        return createRouter({
            routeTree: root.addChildren(routes),
            history: createMemoryHistory({ initialEntries: ["/calls"] }),
        });
    });
    return (
        <Flag.Provider value={{ collapsed, toggle: () => setCollapsed((c) => !c) }}>
            <RouterProvider router={router} />
        </Flag.Provider>
    );
}

const meta = { component: Harness } satisfies Meta<typeof Harness>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Expanded: Story = {
    args: { collapsed: false },
    play: async ({ canvasElement }) => {
        const nav = await within(canvasElement).findByRole("navigation", { name: "Admin screens" });
        for (const g of NAV_GROUPS) {
            await expect(within(nav).getByRole("group", { name: g.label })).toBeVisible();
        }
        await expect(within(nav).getByRole("button", { name: "Sidebar" })).toHaveAttribute(
            "aria-expanded",
            "true",
        );
    },
};

export const Collapsed: Story = {
    args: { collapsed: true },
    play: async ({ canvasElement }) => {
        const nav = await within(canvasElement).findByRole("navigation", { name: "Admin screens" });
        // Icon-only, yet every link keeps its name.
        for (const id of NAV_GROUPS.flatMap((g) => g.pages)) {
            await expect(within(nav).getByRole("link", { name: id })).toBeInTheDocument();
        }
        await expect(within(nav).getByRole("button", { name: "Sidebar" })).toHaveAttribute(
            "aria-expanded",
            "false",
        );
    },
};

/** Below `md` the sidebar is the bottom bar: one scrolling row, no group labels, no toggle. */
export const Phone: Story = {
    args: { collapsed: false },
    globals: { viewport: { value: "mobile1", isRotated: false } },
    play: async ({ canvasElement }) => {
        const nav = await within(canvasElement).findByRole("navigation", { name: "Admin screens" });
        await expect(within(nav).getByTitle("Collapse the sidebar")).not.toBeVisible();
        await expect(window.innerWidth).toBeLessThan(768);
        await expect(getComputedStyle(nav).overflowX).toBe("auto");
        await expect(getComputedStyle(nav).flexDirection).toBe("row");
    },
};

export const Toggles: Story = {
    args: { collapsed: false },
    play: async ({ canvasElement }) => {
        const toggle = await within(canvasElement).findByRole("button", { name: "Sidebar" });
        await userEvent.click(toggle);
        await expect(toggle).toHaveAttribute("aria-expanded", "false");
        await userEvent.keyboard("{Enter}");
        await expect(toggle).toHaveAttribute("aria-expanded", "true");
    },
};
