// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { done, NOW, running, view } from "./callsFixtures";
import { LiveMetrics, STRIP_BELOW_PX } from "./LiveMetrics";

/** A viewport `px` wide: `matchMedia` answers the `min-width` queries the page asks. */
const viewport = (px: number) =>
    vi.stubGlobal("matchMedia", (q: string) => ({
        matches: Number(/min-width: (\d+)px/.exec(q)?.[1] ?? 0) <= px,
        addEventListener() {},
        removeEventListener() {},
    }));

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

const calls = view({
    running: [running("a")],
    feed: [done("b", 1000, 250), done("c", 400, 100)],
});

/** The way the live part holds it: the window and the freeze are the parent's state. */
function Host() {
    const [window, setWindow] = useState(1);
    const [frozen, setFrozen] = useState(false);
    return (
        <LiveMetrics
            view={calls}
            now={NOW}
            window={window}
            onWindow={setWindow}
            frozen={frozen}
            pending={0}
            onFreeze={setFrozen}
        />
    );
}

const strip = () => screen.getByRole("button", { expanded: false }) as HTMLElement;

describe("metrics strip", () => {
    test("under 900 px it is one line with running, calls and saved, and the cards stay shut", () => {
        viewport(STRIP_BELOW_PX - 1);
        render(<Host />);
        const line = screen.getByRole("button", { name: /running 1 calls 2 saved 1\.1k 5 min/ });
        expect(line.getAttribute("aria-expanded")).toBe("false");
        expect(within(line).getByText("5 min")).toBeTruthy();
        expect(screen.queryByText("latency p50")).toBeNull();
        expect(screen.queryByRole("group", { name: "window" })).toBeNull();
        expect(screen.queryByRole("list", { name: "calls per tool" })).toBeNull();
    });

    test("it opens the full cards and shuts them again", () => {
        viewport(STRIP_BELOW_PX - 1);
        render(<Host />);
        fireEvent.click(strip());
        const line = screen.getByRole("button", { name: /running 1 calls 2 saved/ });
        expect(line.getAttribute("aria-expanded")).toBe("true");
        const region = document.getElementById(line.getAttribute("aria-controls")!)!;
        expect(within(region).getByText("latency p50")).toBeTruthy();
        expect(within(region).getByRole("list", { name: "calls per tool" })).toBeTruthy();

        fireEvent.click(line);
        expect(line.getAttribute("aria-expanded")).toBe("false");
        expect(screen.queryByText("latency p50")).toBeNull();
        expect(document.getElementById(line.getAttribute("aria-controls")!)).toBeNull();
    });

    test("opened, it stays opened through a window chip and the freeze, which it also reports", () => {
        viewport(STRIP_BELOW_PX - 1);
        render(<Host />);
        fireEvent.click(strip());
        fireEvent.click(screen.getByRole("button", { name: "1 min" }));
        fireEvent.click(screen.getByRole("button", { name: "Freeze" }));
        const line = screen.getByRole("button", { name: /running 1 calls 2 saved/ });
        expect(line.getAttribute("aria-expanded")).toBe("true");
        expect(within(line).getByText("frozen")).toBeTruthy();
        expect(within(line).getByText("1 min")).toBeTruthy();
        expect(screen.getByText("latency p50")).toBeTruthy();
    });

    test("from 900 px up there is no strip and the cards are the page as before", () => {
        viewport(STRIP_BELOW_PX);
        render(<Host />);
        expect(screen.queryByRole("button", { name: /running 1 calls/ })).toBeNull();
        expect(screen.getByText("latency p50")).toBeTruthy();
        expect(screen.getByRole("group", { name: "window" })).toBeTruthy();
    });

    test("without matchMedia the page reads wide, as the other tests of it do", () => {
        render(<Host />);
        expect(screen.queryByRole("button", { name: /running 1 calls/ })).toBeNull();
        expect(screen.getByText("latency p50")).toBeTruthy();
    });
});
