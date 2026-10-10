// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { expect, waitFor, within } from "storybook/test";
import { project } from "../../../api/sampleRows";
import type { Frame, Connect } from "../../../api/ws";
import { richSnapshot } from "../../fixtures";
import { drawn } from "../../canvasPixels";
import { withData } from "../../storyData";
import { VIEW_KEY } from "../SceneView";
import { done, running, view } from "./callsFixtures";
import { LiveSection } from "./LiveSection";

const projects = [
    project(1, "rtok", {
        selected: true,
        links: [{ to: 2, kind: "manual", name: "ketch", reason: null }],
    }),
    project(2, "ketch"),
    project(3, "pyrlyn"),
];
const snapshot = { ...richSnapshot, projects };

/** A server that plays `frames` once the page subscribes to the call stream. */
const streaming =
    (frames: Frame[], delay = 0): Connect =>
    (h) => {
        h.onState("open");
        h.onFrame({ type: "snapshot", snapshot });
        return {
            send: (m) => {
                if ("calls" in m && m.calls.subscribe)
                    setTimeout(() => frames.forEach(h.onFrame), delay);
                return true;
            },
            close: () => {},
        };
    };

/** The stories pick the view through the same key a reload reads. */
const viewing =
    (view: string): Decorator =>
    (Story) => {
        try {
            localStorage.setItem(VIEW_KEY, view);
        } catch {
            // Storage blocked: the default (3D) applies.
        }
        return <Story />;
    };

/** The lazy 3D chunk and the layout worker outlast the default 1 s wait on a busy host. */
const READY = { timeout: 10_000 };

const meta = {
    title: "Pages/Live graph",
    component: LiveSection,
    args: { rows: projects, drill: null },
    // 2D unless a story asks otherwise: the rings are SVG, and the canvas below is WebGL.
    decorators: [withData(streaming([])), viewing("2d")],
} satisfies Meta<typeof LiveSection>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Waiting: Story = {
    play: async ({ canvasElement }) => {
        await waitFor(() =>
            expect(within(canvasElement).getByText("Waiting for graph calls")).toBeTruthy(),
        );
    },
};

const calls = (): Frame[] => [
    {
        type: "calls",
        calls: view({
            running: [
                running("d", { project: "pyrlyn", target: "run" }),
                running("e", { project: "ketch", target: "go", tool: "explore" }),
            ],
            feed: [
                done("c", 0, 0, {
                    target: "missing",
                    project: "rtok",
                    ok: false,
                    error: "no backend",
                }),
                done("b", 400, 100, { target: "store", project: "ketch", tool: "impact" }),
                done("a", 1000, 250, { target: "open_index", project: "rtok", backend: "lsp" }),
            ],
        }),
    },
];

export const Busy: Story = {
    decorators: [withData(streaming(calls()))],
    play: async ({ canvasElement }) => {
        const view = within(canvasElement);
        await waitFor(() => expect(view.getByText("no backend")).toBeTruthy());
        expect(view.getByRole("list", { name: "running calls" })).toBeTruthy();
        await waitFor(
            () => expect(canvasElement.querySelectorAll("[data-testid=accent]").length).toBe(2),
            READY,
        );
        expect(canvasElement.querySelectorAll("[data-testid=failed]").length).toBe(1);
    },
};

const runningFrames = (): Frame[] => [
    {
        type: "calls",
        calls: view({ running: [running("d", { project: "pyrlyn", target: "run" })] }),
    },
];

const viewBox = (svg: Element) => svg.getAttribute("viewBox")!.split(" ").map(Number);

/** Nothing running: the camera shows the whole picture, wider than any frame. */
export const IdleOverview2d: Story = {
    decorators: [viewing("2d")],
    play: async ({ canvasElement }) => {
        const svg = await within(canvasElement).findByTestId("graph-live", undefined, READY);
        await waitFor(() => expect(viewBox(svg)[3]).toBeGreaterThan(140), READY);
    },
};

/** A running call: the 2D camera closes in on its node (140 is the smallest frame). */
export const CameraFramesRunningCall2d: Story = {
    decorators: [withData(streaming(runningFrames())), viewing("2d")],
    play: async ({ canvasElement }) => {
        const svg = await within(canvasElement).findByTestId("graph-live", undefined, READY);
        await waitFor(() => expect(viewBox(svg)[3]).toBe(140), READY);
        await waitFor(() => expect(svg.querySelectorAll("[data-testid=accent]")).toHaveLength(1));
    },
};

/** The same call in the Three.js canvas: drawn, read-only, and the camera holds the node. */
export const Live3d: Story = {
    decorators: [withData(streaming(runningFrames(), 1500)), viewing("3d")],
    play: async ({ canvasElement }) => {
        const host = await within(canvasElement).findByTestId("graph-live-3d", undefined, READY);
        const canvas = () => {
            const c = host.querySelector("canvas");
            if (!c) throw new Error("no canvas yet");
            return c;
        };
        await waitFor(() => expect(drawn(canvas())).toBeGreaterThan(200), READY);
        await waitFor(() => expect(host.dataset["framed"]).toBeTruthy(), READY);
        // Still drawn once the camera has moved: the node fills the view, it does not leave it.
        await waitFor(() => expect(drawn(canvas())).toBeGreaterThan(200), READY);
        // The stage is rebuilt when the layout restarts, so the canvas is looked up afresh.
        expect(getComputedStyle(canvas()).pointerEvents).toBe("none");
    },
};

export const Live3dLight: Story = { ...Live3d, globals: { theme: "light" } };
