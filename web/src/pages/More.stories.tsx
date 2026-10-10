// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Meta, StoryObj } from "@storybook/react-vite";
import type { ReactElement } from "react";
import { expect, within } from "storybook/test";
import { Config } from "./Config";
import { richSnapshot } from "./fixtures";
import { Graph } from "./Graph";
import { Hosts } from "./Hosts";
import { Services } from "./Services";
import { Skills } from "./Skills";
import { Stats } from "./Stats";
import { serve, withData } from "./storyData";
import { graphScopedText } from "./textFixtures";
import { Worktrees } from "./Worktrees";

const rich = serve(richSnapshot);
// The server could not produce a text page this tick: every text field is `null`.
const unanswered = serve({
    ...richSnapshot,
    stats: null,
    graph: null,
    config: null,
    services: null,
    worktrees: null,
});
const quiet = serve({
    ...richSnapshot,
    skills: { header: "", rows: [] },
    hosts: "probing hosts…\n",
    worktrees: "not a git repository\n",
});

const meta = { title: "Pages/More" } satisfies Meta;
export default meta;

const page = (render: () => ReactElement, connect = rich): StoryObj => ({
    render,
    decorators: [withData(connect)],
});
const light = (s: StoryObj): StoryObj => ({ ...s, globals: { theme: "light" } });

export const SkillsDefault = page(() => <Skills />);
export const SkillsLight = light(SkillsDefault);
export const SkillsEmpty = page(() => <Skills />, quiet);

export const StatsDefault = page(() => <Stats />);
export const StatsLight = light(StatsDefault);
export const StatsUnanswered = page(() => <Stats />, unanswered);

export const GraphDefault = page(() => <Graph />);
export const GraphLight = light(GraphDefault);
// A project and its linked project: every pending and dead row carries a project badge (T329.21).
export const GraphScoped: StoryObj = {
    ...page(() => <Graph />, serve({ ...richSnapshot, graph: graphScopedText })),
    play: async ({ canvasElement }) => {
        const table = await within(canvasElement).findByRole("table", { name: "dead symbols" });
        const [rtok, ketch] = within(table).getAllByRole("row").slice(1);
        await expect(within(rtok!).getByText("rtok")).toBeVisible();
        await expect(within(ketch!).getByText("ketch")).toBeVisible();
    },
};
export const GraphOff = page(() => <Graph />, unanswered);

export const HostsDefault = page(() => <Hosts />);
export const HostsLight = light(HostsDefault);
export const HostsProbing = page(() => <Hosts />, quiet);

export const ConfigDefault = page(() => <Config />);
export const ConfigLight = light(ConfigDefault);
export const ConfigUnanswered = page(() => <Config />, unanswered);

export const ServicesDefault = page(() => <Services />);
export const ServicesLight = light(ServicesDefault);
export const ServicesUnanswered = page(() => <Services />, unanswered);

export const WorktreesDefault = page(() => <Worktrees />);
export const WorktreesLight = light(WorktreesDefault);
export const WorktreesNotARepo = page(() => <Worktrees />, quiet);
