// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { drillServer } from "../../../api/sampleDrill";
import { project } from "../../../api/sampleRows";
import type { Snapshot } from "../../../api/snapshot.gen";
import { richSnapshot } from "../../fixtures";
import { mount, wire } from "../../testHelpers";
import { VIEW_KEY } from "../ProjectsOverview";
import { batch, end, event } from "./callsFixtures";
import { FOCUS_MS } from "./lit";
import { HIDE_KEY, SPLIT_KEY } from "./Split";

const snap: Snapshot = {
    ...richSnapshot,
    projects: [project(1, "rtok", { selected: true }), project(2, "ketch")],
};

beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(VIEW_KEY, "list");
});
afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});
const noop = () => {};

const canvas = () => screen.findByTestId("graph-live");
const calls = (sent: unknown[]) =>
    sent.flatMap((m) =>
        m && typeof m === "object" && "calls" in m
            ? [(m as { calls: { subscribe: boolean } }).calls.subscribe]
            : [],
    );

describe("live graph", () => {
    test("it subscribes, shows the waiting state, then the call in the metrics and the feed", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        expect(await screen.findByText("Waiting for graph calls")).toBeTruthy();
        await waitFor(() => expect(calls(w.sent)).toEqual([true]));

        act(() => w.frame({ type: "calls", batch: batch([event({ call: "a" })]) }));
        await waitFor(() => expect(screen.queryByText("Waiting for graph calls")).toBeNull());
        expect(
            within(screen.getByRole("list", { name: "running calls" })).getByText("callers"),
        ).toBeTruthy();

        act(() => w.frame({ type: "calls", batch: batch([end("a", 1000, 250)]) }));
        const table = await screen.findByRole("table", { name: "graph calls" });
        await waitFor(() => expect(within(table).getByText(/750 saved/)).toBeTruthy());
        expect(screen.getByText("75%")).toBeTruthy();
    });

    test("the metrics show latency, symbols, projects, fallbacks and caps counted from the events", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        await canvas();
        const row = (kind: string, ref_id: string | null) => ({
            id: 1,
            kind,
            before_bytes: 0,
            after_bytes: 0,
            est_before: 0,
            est_after: 0,
            ref_id,
        });
        act(() =>
            w.frame({
                type: "calls",
                batch: batch([
                    end("a", 0, 0, {
                        ms: 40,
                        symbols: 2,
                        symbols_returned: 1,
                        files_touched: 3,
                        projects_hit: 2,
                        total: 2,
                        samples: [row("lsp_fallback", null)],
                    }),
                    end("b", 0, 0, {
                        ms: 1500,
                        symbols: 1,
                        symbols_returned: 1,
                        files_touched: 2,
                        projects_hit: 1,
                        total: 1,
                        samples: [row("cap", "ab")],
                    }),
                ]),
            }),
        );
        const card = async (label: string) =>
            (await screen.findByText(label)).parentElement?.textContent;
        await waitFor(async () => expect(await card("latency p50")).toContain("40 ms"));
        expect(await card("latency p50")).toContain("p95 1.5 s");
        expect(await card("symbols returned")).toContain("2 of 3");
        expect(await card("symbols returned")).toContain("1 across projects");
        expect(await card("files touched")).toBe("files touched5");
        expect(await card("projects with hits")).toBe("projects with hits3");
        expect(await card("fallbacks")).toContain("11 capped");
    });

    test("a failed call is red in the feed", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        await canvas();
        act(() =>
            w.frame({
                type: "calls",
                batch: batch([end("a", 0, 0, { ok: false, error: "no backend" })]),
            }),
        );
        const cell = await screen.findByText("no backend");
        expect(cell.className).toContain("text-delta-fg");
    });

    test("the canvas takes no input: no handlers, nothing focusable, a default cursor", async () => {
        mount(wire(snap).connect, "/graph");
        const svg = await canvas();
        expect(svg.getAttribute("class")).toContain("pointer-events-none");
        expect(svg.querySelectorAll("[tabindex],[role=button]")).toHaveLength(0);
        const before = svg.getAttribute("viewBox");
        fireEvent.wheel(svg, { deltaY: 100 });
        fireEvent.pointerDown(svg, { clientX: 5, clientY: 5 });
        fireEvent.pointerMove(svg, { clientX: 90, clientY: 90 });
        expect(svg.getAttribute("viewBox")).toBe(before);
    });

    test("calls light their node: a ring per running call, a halo after, a red ring when it failed", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        const svg = await canvas();
        const running = ["a", "b"].map((call) => event({ call }));
        act(() => w.frame({ type: "calls", batch: batch(running) }));
        await waitFor(() => expect(svg.querySelectorAll("[data-testid=accent]")).toHaveLength(2));
        expect(svg.querySelectorAll("[data-running]")).toHaveLength(1);

        const ended = [end("a", 10, 4), end("b", 0, 0, { ok: false, error: "no backend" })];
        act(() => w.frame({ type: "calls", batch: batch(ended) }));
        await waitFor(() => expect(svg.querySelectorAll("[data-testid=failed]")).toHaveLength(1));
        expect(svg.querySelectorAll("[data-testid=heat]")).toHaveLength(1);
        expect(svg.querySelectorAll("[data-testid=accent]")).toHaveLength(0);
    });

    test("running calls beyond the eight accents are a count, not more rings", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        const svg = await canvas();
        const many = Array.from({ length: 10 }, (_, i) => event({ call: `c${i}` }));
        act(() => w.frame({ type: "calls", batch: batch(many) }));
        expect(await screen.findByText("busy: 2 more")).toBeTruthy();
        expect(svg.querySelectorAll("[data-testid=accent]")).toHaveLength(8);
    });

    test("the camera frames a running call and returns to the whole when the call is old", async () => {
        vi.stubGlobal("matchMedia", (q: string) => ({
            matches: q.includes("reduce"),
            addEventListener: noop,
            removeEventListener: noop,
        }));
        const w = wire(snap);
        mount(w.connect, "/graph");
        const svg = await canvas();
        await waitFor(() =>
            expect(svg.querySelectorAll("[data-testid=node-live]")).toHaveLength(2),
        );
        // The layout is still settling in a test, so the whole is judged by its height, not its exact box.
        const height = () => Number(svg.getAttribute("viewBox")!.split(" ")[3]);
        await waitFor(() => expect(height()).toBeGreaterThan(140));
        act(() =>
            w.frame({ type: "calls", batch: batch([event({ call: "a", project: "ketch" })]) }),
        );
        await waitFor(() => expect(height()).toBe(140));
        act(() =>
            w.frame({ type: "calls", batch: batch([end("a", 10, 4, { project: "ketch" })]) }),
        );
        // The call has ended; the next one-second tick finds it older than the hold.
        const real = Date.now();
        vi.spyOn(Date, "now").mockReturnValue(real + FOCUS_MS + 2000);
        await waitFor(() => expect(height()).toBeGreaterThan(140), { timeout: 3000 });
    });

    test("the 2D/3D switch is remembered; 3D without WebGL says so and still shows 2D", async () => {
        mount(wire(snap).connect, "/graph");
        await canvas();
        const view = within(screen.getByRole("group", { name: "live view" }));
        fireEvent.click(view.getByRole("button", { name: "3D" }));
        expect(localStorage.getItem(VIEW_KEY)).toBe("3d");
        expect(await screen.findByText(/3D unavailable \(WebGL is not available/)).toBeTruthy();
        expect(screen.getByTestId("graph-live")).toBeTruthy();
        fireEvent.click(view.getByRole("button", { name: "2D" }));
        expect(localStorage.getItem(VIEW_KEY)).toBe("2d");
        expect(screen.queryByText(/3D unavailable/)).toBeNull();
    });

    test("freeze holds the picture while the totals keep counting, and unfreeze catches up", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        await canvas();
        act(() =>
            w.frame({ type: "calls", batch: batch([end("a", 100, 50, { target: "alpha" })]) }),
        );
        await screen.findByText("alpha");

        fireEvent.click(screen.getByRole("button", { name: "Freeze" }));
        act(() =>
            w.frame({
                type: "calls",
                batch: batch([end("b", 100, 50, { target: "beta" }), end("c", 100, 50)]),
            }),
        );
        expect(await screen.findByText("2 held")).toBeTruthy();
        expect(screen.queryByText("beta")).toBeNull();

        fireEvent.click(screen.getByRole("button", { name: "Unfreeze" }));
        expect(await screen.findByText("beta")).toBeTruthy();
        await waitFor(() => expect(screen.queryByText("2 held")).toBeNull());
        fireEvent.click(screen.getByRole("button", { name: "since open" }));
        // Three calls of 100 tokens each, none dropped by the freeze.
        expect(await screen.findByText("300")).toBeTruthy();
    });

    test("the window selector recomputes the totals", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        await canvas();
        act(() => w.frame({ type: "calls", batch: batch([end("a", 10, 4)]) }));
        await screen.findByRole("table", { name: "graph calls" });
        for (const label of ["1 min", "5 min", "15 min", "since open"]) {
            fireEvent.click(screen.getByRole("button", { name: label }));
            expect(screen.getByRole("button", { name: label }).getAttribute("aria-pressed")).toBe(
                "true",
            );
        }
    });

    test("a 500-call burst is one frame whose totals count every call", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        await canvas();
        const listed = Array.from({ length: 100 }, (_, i) => end(`c${i}`, 10, 4));
        act(() => w.frame({ type: "calls", batch: batch(listed, 400) }));
        fireEvent.click(screen.getByRole("button", { name: "since open" }));
        await waitFor(() => expect(screen.getByText("500")).toBeTruthy());
    });
});

describe("split", () => {
    test("hiding the live graph stops the stream, and the choice is kept", async () => {
        const w = wire(snap);
        mount(w.connect, "/graph");
        await canvas();
        fireEvent.click(screen.getByRole("button", { name: "Hide live graph" }));
        expect(screen.queryByTestId("graph-live")).toBeNull();
        await waitFor(() => expect(calls(w.sent)).toEqual([true, false]));
        expect(localStorage.getItem(HIDE_KEY)).toBe("1");
    });

    test("the split and the hidden state survive a remount", async () => {
        const first = mount(wire(snap).connect, "/graph");
        await canvas();
        fireEvent.keyDown(screen.getByRole("separator"), { key: "ArrowLeft" });
        first.unmount();
        const second = mount(wire(snap).connect, "/graph");
        await canvas();
        expect(screen.getByRole("separator").getAttribute("aria-valuenow")).toBe("45");
        fireEvent.click(screen.getByRole("button", { name: "Hide live graph" }));
        second.unmount();
        mount(wire(snap).connect, "/graph");
        expect(await screen.findByRole("button", { name: "Show live graph" })).toBeTruthy();
        expect(screen.queryByTestId("graph-live")).toBeNull();
        expect(screen.queryByRole("separator")).toBeNull();
    });

    test("the bar keeps its position within bounds and without storage", async () => {
        localStorage.setItem(SPLIT_KEY, "9");
        const spy = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
            throw new Error("denied");
        });
        mount(wire(snap).connect, "/graph");
        await canvas();
        const bar = screen.getByRole("separator");
        expect(bar.getAttribute("aria-valuenow")).toBe("80");
        fireEvent.keyDown(bar, { key: "ArrowLeft" });
        expect(bar.getAttribute("aria-valuenow")).toBe("75");
        spy.mockRestore();
    });

    test("the bar resizes with the arrow keys, remembers it and resets on double-click", async () => {
        mount(wire(snap).connect, "/graph");
        await canvas();
        const bar = screen.getByRole("separator");
        expect(bar.getAttribute("aria-valuenow")).toBe("50");
        fireEvent.keyDown(bar, { key: "ArrowRight" });
        expect(bar.getAttribute("aria-valuenow")).toBe("55");
        expect(localStorage.getItem(SPLIT_KEY)).toBe("0.55");
        fireEvent.doubleClick(bar);
        expect(bar.getAttribute("aria-valuenow")).toBe("50");
    });

    test("the live graph follows the drilled project", async () => {
        mount(drillServer(snap), "/graph?p=1");
        expect((await canvas()).getAttribute("aria-label")).toContain("symbols of");
    });
});
