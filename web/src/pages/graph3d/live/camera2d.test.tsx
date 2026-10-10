// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, render } from "@testing-library/react";
import { createRef } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { project } from "../../../api/sampleRows";
import { buildScene } from "../scene";
import Scene2D from "../Scene2D";
import type { Positions, Vec3 } from "../useLayout";
import type { ViewApi } from "../webgl";
import { EASE_MS } from "./camera";
import type { LiveState } from "./lit";

const scene = buildScene([project(1, "rtok"), project(2, "ketch")], {
    query: "",
    scopeOnly: false,
});
const [a, b] = scene.nodes.map((n) => n.id) as [number, number];
const positions: Positions = {
    map: new Map<number, Vec3>([
        [a, [0, 0, 0]],
        [b, [500, 0, 0]],
    ]),
    settled: true,
    subscribe: () => () => {},
};
const idle: LiveState = { lit: new Map(), busy: 0, focus: [], group: 0 };
const on = (...focus: number[]): LiveState => ({ ...idle, focus });
const noop = () => {};

function mount(live: LiveState) {
    const el = (l: LiveState) => (
        <Scene2D
            scene={scene}
            positions={positions}
            api={createRef<ViewApi | null>()}
            live={l}
            select={noop}
            menu={noop}
            hover={noop}
        />
    );
    const out = render(el(live));
    const box = () =>
        out.getByTestId("graph-live").getAttribute("viewBox")!.split(" ").map(Number) as [
            number,
            number,
            number,
            number,
        ];
    return { box, set: (l: LiveState) => out.rerender(el(l)) };
}

const reduced = (on: boolean) =>
    vi.stubGlobal("matchMedia", (q: string) => ({ matches: on && q.includes("reduce") }));

beforeEach(() => vi.useFakeTimers({ toFake: ["requestAnimationFrame", "performance"] }));
afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
});

describe("the live 2D camera", () => {
    test("with reduced motion it jumps to the running call and back to the whole", () => {
        reduced(true);
        const view = mount(idle);
        const whole = view.box();
        view.set(on(b));
        const framed = view.box();
        expect(framed[2]).toBeLessThan(whole[2]);
        // ketch is at x = 500: the framed box is centred on it.
        expect(framed[0] + framed[2] / 2).toBeCloseTo(500);
        view.set(idle);
        expect(view.box()).toEqual(whole);
    });

    test("otherwise it eases: part-way after half the time, there after all of it", () => {
        reduced(false);
        const view = mount(idle);
        const whole = view.box();
        view.set(on(b));
        expect(view.box()).toEqual(whole);
        act(() => void vi.advanceTimersByTime(EASE_MS / 2));
        const mid = view.box();
        expect(mid[2]).toBeLessThan(whole[2]);
        act(() => void vi.advanceTimersByTime(EASE_MS));
        const framed = view.box();
        expect(framed[2]).toBeLessThan(mid[2]);
        expect(framed[0] + framed[2] / 2).toBeCloseTo(500);

        view.set(idle);
        act(() => void vi.advanceTimersByTime(EASE_MS * 2));
        expect(view.box()).toEqual(whole);
    });

    test("a new frame while one is in flight starts from where the picture is", () => {
        reduced(false);
        const view = mount(idle);
        view.set(on(b));
        act(() => void vi.advanceTimersByTime(EASE_MS / 2));
        const mid = view.box();
        view.set(on(a));
        act(() => void vi.advanceTimersByTime(16));
        // No jump back to the whole or ahead to the first target.
        const next = view.box();
        expect(Math.abs(next[0] - mid[0])).toBeLessThan(150);
        act(() => void vi.advanceTimersByTime(EASE_MS * 2));
        expect(view.box()[0] + view.box()[2] / 2).toBeCloseTo(0);
    });

    test("the same frame under a moving layout follows it at once", () => {
        reduced(false);
        const view = mount(on(b));
        positions.map.set(b, [800, 0, 0]);
        view.set(on(b));
        act(() => void vi.advanceTimersByTime(16));
        expect(view.box()[0] + view.box()[2] / 2).toBeCloseTo(800);
        positions.map.set(b, [500, 0, 0]);
    });
});
