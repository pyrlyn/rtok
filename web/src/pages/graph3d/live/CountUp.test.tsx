// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { compact } from "../../format";
import { CountUp, COUNT_MS, useCountUp } from "./CountUp";

const reduced = (on: boolean) =>
    vi.stubGlobal("matchMedia", (q: string) => ({ matches: on && q.includes("reduce") }));

/** The drawn number while it is counting: the aria-hidden span. */
const drawn = () =>
    screen.getByText((_, el) => el?.getAttribute("aria-hidden") === "true")!.textContent;
/** The server's total: the sr-only twin while counting, the only node once it lands. */
const total = () => screen.queryByText((_, el) => el?.className === "sr-only")?.textContent ?? null;

beforeEach(() => vi.useFakeTimers({ toFake: ["requestAnimationFrame", "performance"] }));
afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
});

describe("CountUp", () => {
    test("it counts up on a new frame and lands on the server's value", () => {
        reduced(false);
        const { rerender } = render(<CountUp value={0} />);
        expect(screen.getByText("0")).toBeTruthy();
        rerender(<CountUp value={100} />);
        expect(drawn()).toBe("0");
        expect(total()).toBe("100");
        act(() => void vi.advanceTimersByTime(COUNT_MS / 2));
        const mid = Number(drawn()!.replace(/,/g, ""));
        expect(mid).toBeGreaterThan(0);
        expect(mid).toBeLessThan(100);
        act(() => void vi.advanceTimersByTime(COUNT_MS));
        expect(screen.getByText("100")).toBeTruthy();
        expect(total()).toBeNull();
    });

    test("a whole total counts through whole numbers, a fractional one does not", () => {
        reduced(false);
        const { rerender } = render(<CountUp value={0} />);
        rerender(<CountUp value={7} />);
        for (let i = 0; i < 8; i++) {
            act(() => void vi.advanceTimersByTime(COUNT_MS / 8));
            const frame = screen.queryByText((_, el) => el?.getAttribute("aria-hidden") === "true");
            if (frame) expect(frame.textContent).toMatch(/^\d+$/);
        }
        rerender(<CountUp value={0.5} />);
        act(() => void vi.advanceTimersByTime(COUNT_MS / 2));
        expect(drawn()).toMatch(/\./);
    });

    test("with prefers-reduced-motion it jumps", () => {
        reduced(true);
        const { rerender } = render(<CountUp value={0} />);
        rerender(<CountUp value={100} />);
        expect(screen.getByText("100")).toBeTruthy();
        expect(drawn).toThrow();
    });

    test("assistive tech reads the server's total only, never the in-between frames", () => {
        reduced(false);
        const { rerender } = render(<CountUp value={0} />);
        rerender(<CountUp value={1_234} />);
        act(() => void vi.advanceTimersByTime(COUNT_MS / 4));
        // The animated span is hidden; the accessible name is already the final total.
        expect(screen.getByText("1,234").getAttribute("class")).toBe("sr-only");
        expect(screen.getByText("1,234").previousElementSibling?.getAttribute("aria-hidden")).toBe(
            "true",
        );
        expect(screen.getByText("1,234").previousElementSibling?.textContent).not.toBe("1,234");
    });

    test("a custom format carries the animation (compact tokens)", () => {
        reduced(true);
        const { rerender } = render(<CountUp value={0} format={compact} />);
        rerender(<CountUp value={1_500} format={compact} />);
        expect(screen.getByText("1.5k")).toBeTruthy();
    });

    test("a value that moves mid-count is followed, not restarted from zero", () => {
        reduced(false);
        const { rerender } = render(<CountUp value={0} />);
        rerender(<CountUp value={100} />);
        act(() => void vi.advanceTimersByTime(COUNT_MS / 2));
        const mid = Number(drawn()!.replace(/,/g, ""));
        rerender(<CountUp value={200} />);
        act(() => void vi.advanceTimersByTime(16));
        // No jump back to 0 or straight to 100/200: it continues from around where it was.
        const next = Number(drawn()!.replace(/,/g, ""));
        expect(Math.abs(next - mid)).toBeLessThan(30);
        act(() => void vi.advanceTimersByTime(COUNT_MS * 2));
        expect(screen.getByText("200")).toBeTruthy();
    });
});

describe("useCountUp", () => {
    test("it starts at the first value and eases to the next", () => {
        reduced(false);
        const values: number[] = [];
        function Probe({ value }: { value: number }) {
            values.push(useCountUp(value));
            return null;
        }
        const { rerender } = render(<Probe value={1} />);
        expect(values.at(-1)).toBe(1);
        rerender(<Probe value={3} />);
        act(() => void vi.advanceTimersByTime(COUNT_MS));
        expect(values.at(-1)).toBe(3);
    });
});
