// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, render, screen } from "@testing-library/react";
import type { RefObject } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { ViewApi } from "../webgl";
import { NOW } from "./callsFixtures";
import { type CallLabel, LABEL_HOLD_MS } from "./lit";
import { labelText, MAX_LINES, RunningLabels } from "./RunningLabels";

const label = (call: string, over: Partial<CallLabel> = {}): CallLabel => ({
    call,
    node: 1,
    tool: "callers",
    at: NOW - 3000,
    ms: null,
    endedAt: null,
    failed: false,
    accent: 0,
    folded: false,
    ...over,
});

/** A view that puts every node at one place, inside a 400 x 300 canvas at the page's origin. */
const viewAt = (p: { x: number; y: number } | null): RefObject<ViewApi | null> => ({
    current: { fit() {}, reset() {}, focus() {}, screenOf: () => p },
});

beforeEach(() => {
    vi.useFakeTimers({ toFake: ["requestAnimationFrame", "cancelAnimationFrame"] });
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
        left: 0,
        top: 0,
        width: 400,
        height: 300,
        right: 400,
        bottom: 300,
        x: 0,
        y: 0,
        toJSON() {},
    });
});
afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
});

const frame = () => act(() => void vi.advanceTimersByTime(20));
const group = () => document.querySelector<HTMLElement>("[data-node]")!;

describe("labelText", () => {
    test("a running call counts seconds from its start, a finished one shows how long it ran", () => {
        expect(labelText(label("a"), NOW)).toBe("callers 3s");
        expect(labelText(label("a"), NOW + 2000)).toBe("callers 5s");
        expect(labelText(label("a", { ms: 420, endedAt: NOW }), NOW)).toBe("callers 420 ms");
        expect(labelText(label("a", { ms: null, endedAt: NOW }), NOW)).toBe("callers");
    });
});

describe("RunningLabels", () => {
    test("a label follows its node on every frame and sits to the right of it", () => {
        const api = viewAt({ x: 100, y: 80 });
        render(<RunningLabels labels={[label("a")]} now={NOW} api={api} />);
        frame();
        expect(group().style.visibility).toBe("visible");
        expect(group().style.transform).toBe("translate(112px, 72px)");

        api.current = { ...api.current!, screenOf: () => ({ x: 150, y: 90 }) };
        frame();
        expect(group().style.transform).toBe("translate(162px, 82px)");
    });

    test("it is hidden while the node has no position or is off the canvas", () => {
        const api = viewAt(null);
        render(<RunningLabels labels={[label("a")]} now={NOW} api={api} />);
        frame();
        expect(group().style.visibility).toBe("hidden");
        api.current = { ...api.current!, screenOf: () => ({ x: 900, y: 80 }) };
        frame();
        expect(group().style.visibility).toBe("hidden");
    });

    test("a view that throws while it is torn down does not stop the loop", () => {
        const api = viewAt({ x: 10, y: 10 });
        api.current!.screenOf = () => {
            throw new Error("disposed");
        };
        render(<RunningLabels labels={[label("a")]} now={NOW} api={api} />);
        frame();
        expect(group().style.visibility).toBe("hidden");
        api.current!.screenOf = () => ({ x: 10, y: 10 });
        frame();
        expect(group().style.visibility).toBe("visible");
    });

    test("it is a picture: the overlay is out of the accessibility tree", () => {
        render(<RunningLabels labels={[label("a")]} now={NOW} api={viewAt(null)} />);
        expect(screen.getByTestId("run-labels").getAttribute("aria-hidden")).toBe("true");
    });

    test("a finished label holds, fades after the hold and an error one is red", () => {
        const ended = label("a", { ms: 40, endedAt: NOW, accent: null, failed: true });
        const { rerender } = render(
            <RunningLabels labels={[ended]} now={NOW} api={viewAt(null)} />,
        );
        const state = () => screen.getByTestId("run-label").getAttribute("data-state");
        expect(state()).toBe("ended");
        expect(screen.getByTestId("run-label").className).toContain("text-delta-fg");
        expect(screen.getByTestId("run-label").className).toContain("opacity-100");
        rerender(<RunningLabels labels={[ended]} now={NOW + LABEL_HOLD_MS} api={viewAt(null)} />);
        expect(state()).toBe("fading");
        expect(screen.getByTestId("run-label").className).toContain("opacity-0");
    });

    test("the fade is a transition only where motion is welcome", () => {
        render(<RunningLabels labels={[label("a")]} now={NOW} api={viewAt(null)} />);
        expect(screen.getByTestId("run-label").className).toContain(
            "motion-safe:transition-opacity",
        );
    });

    test("a running label takes its call's accent, the second four dashed", () => {
        render(
            <RunningLabels
                labels={[label("a", { accent: 0 }), label("b", { accent: 5 })]}
                now={NOW}
                api={viewAt(null)}
            />,
        );
        const [first, second] = screen.getAllByTestId("run-label");
        expect(first!.style.borderStyle).toBe("solid");
        expect(second!.style.borderStyle).toBe("dashed");
    });

    test("calls on one node stack, and those beyond the cap are counted", () => {
        const many = Array.from({ length: MAX_LINES + 2 }, (_, i) => label(`c${i}`));
        render(<RunningLabels labels={many} now={NOW} api={viewAt(null)} />);
        expect(screen.getAllByTestId("run-label")).toHaveLength(MAX_LINES);
        expect(screen.getByText("+2 calls")).toBeTruthy();
        expect(document.querySelectorAll("[data-node]")).toHaveLength(1);
    });

    test("a node that loses its last label loses its group, and the loop stops with the overlay", () => {
        const { rerender, unmount } = render(
            <RunningLabels labels={[label("a")]} now={NOW} api={viewAt(null)} />,
        );
        rerender(<RunningLabels labels={[]} now={NOW} api={viewAt(null)} />);
        expect(document.querySelectorAll("[data-node]")).toHaveLength(0);
        const cancel = vi.spyOn(globalThis, "cancelAnimationFrame");
        rerender(<RunningLabels labels={[label("a")]} now={NOW} api={viewAt(null)} />);
        unmount();
        expect(cancel).toHaveBeenCalled();
    });
});
