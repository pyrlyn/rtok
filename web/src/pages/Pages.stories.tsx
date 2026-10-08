// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";
import { connectSample } from "../api/sample";
import { Calls } from "./Calls";
import { richSnapshot } from "./fixtures";
import { Overview } from "./Overview";
import { Plugins } from "./Plugins";
import { serve, withData } from "./storyData";

const rich = serve(richSnapshot);
const loading = serve(null);
const noPlugins = serve({ ...richSnapshot, plugins: [] });
const noCalls = serve({ ...richSnapshot, calls: [] });
const noDoctor = serve({ ...richSnapshot, doctor: null, sessions: [] });

const overview = { title: "Pages/Overview", component: Overview } satisfies Meta<typeof Overview>;
export default overview;
type Story = StoryObj<typeof overview>;

export const OverviewDefault: Story = { decorators: [withData(rich)] };
// Every card and panel that has a page links to it with the filter the figure on it describes.
export const OverviewLinks: Story = {
    decorators: [withData(rich)],
    play: async ({ canvasElement }) => {
        const view = within(canvasElement);
        const targets: [string | RegExp, string][] = [
            ["calls", "/calls"],
            [/^1 failed$/, "/calls?result=failed"],
            ["live sessions", "/sessions?show=live"],
            ["plugins on", "/plugins?show=on"],
            [/^hook \d+$/, "/calls?surface=hook"],
            [/^failed 1$/, "/calls?result=failed"],
            [/ live →$/, "/sessions?show=live"],
        ];
        for (const [name, href] of targets) {
            await expect(await view.findByRole("link", { name })).toHaveAttribute("href", href);
        }
    },
};
export const OverviewLoading: Story = { decorators: [withData(loading)] };
export const OverviewNothingMeasured: Story = { decorators: [withData(noPlugins)] };
export const OverviewDoctorUnavailable: Story = { decorators: [withData(noDoctor)] };
export const OverviewLight: Story = { decorators: [withData(rich)], globals: { theme: "light" } };

export const PluginsDefault: StoryObj = { render: () => <Plugins />, decorators: [withData(rich)] };
export const PluginsLight: StoryObj = {
    render: () => <Plugins />,
    decorators: [withData(rich)],
    globals: { theme: "light" },
};
export const PluginsEmpty: StoryObj = {
    render: () => <Plugins />,
    decorators: [withData(noPlugins)],
};
// The sample server applies the toggle, so this is the real round-trip.
export const PluginsToggleAgainstSampleServer: StoryObj = {
    render: () => <Plugins />,
    decorators: [withData(connectSample)],
};

export const CallsDefault: StoryObj = { render: () => <Calls />, decorators: [withData(rich)] };
export const CallsLight: StoryObj = {
    render: () => <Calls />,
    decorators: [withData(rich)],
    globals: { theme: "light" },
};
export const CallsEmpty: StoryObj = { render: () => <Calls />, decorators: [withData(noCalls)] };
