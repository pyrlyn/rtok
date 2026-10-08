// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { DataProvider } from "./api/query";
import { sampleSnapshot } from "./api/sample";
import type { Connect } from "./api/ws";
import { ageLabel, LiveStatus, LiveStatusView } from "./LiveStatus";

afterEach(() => {
    cleanup();
    vi.useRealTimers();
});

describe("ageLabel", () => {
    test("counts seconds, then minutes and hours, from the update to now", () => {
        const at = 1_000_000;
        expect([0, 3_400, 59_000, 125_000, 7_300_000].map((d) => ageLabel(at, at + d))).toEqual([
            "updated 0s ago",
            "updated 3s ago",
            "updated 59s ago",
            "updated 2m ago",
            "updated 2h ago",
        ]);
    });

    test("never goes negative when the clock steps back", () => {
        expect(ageLabel(5_000, 1_000)).toBe("updated 0s ago");
    });
});

describe("LiveStatusView", () => {
    const base = { link: "open", now: 10_000, onPausedChange: () => {} } as const;

    test("live: age, live pill and an unpressed pause button", () => {
        render(<LiveStatusView {...base} updatedAt={7_000} paused={false} />);
        expect(screen.getByText("updated 3s ago")).toBeTruthy();
        expect(screen.getByText("live")).toBeTruthy();
        expect(screen.queryByText("paused")).toBeNull();
        const button = screen.getByRole("button", { name: "Pause live updates" });
        expect(button.getAttribute("aria-pressed")).toBe("false");
        expect(screen.getByText("Live updates on").getAttribute("aria-live")).toBe("polite");
    });

    test("paused: visible pill, announced status and a pressed button", () => {
        render(<LiveStatusView {...base} updatedAt={7_000} paused />);
        expect(screen.getByText("paused")).toBeTruthy();
        expect(screen.getByText("Live updates paused").getAttribute("aria-live")).toBe("polite");
        expect(
            screen.getByRole("button", { name: "Pause live updates" }).getAttribute("aria-pressed"),
        ).toBe("true");
    });

    test("no snapshot yet: no age and nothing to pause", () => {
        render(<LiveStatusView {...base} paused={false} />);
        expect(screen.queryByText(/updated/)).toBeNull();
        expect(
            (screen.getByRole("button", { name: "Pause live updates" }) as HTMLButtonElement)
                .disabled,
        ).toBe(true);
    });
});

describe("LiveStatus", () => {
    test("the age ticks, and pause freezes the snapshot until resume", () => {
        vi.useFakeTimers();
        vi.setSystemTime(1_000_000);
        let frame!: (logs: string[]) => void;
        const connect: Connect = (h) => {
            h.onState("open");
            frame = (logs) =>
                h.onFrame({ type: "snapshot", snapshot: { ...sampleSnapshot, logs } });
            frame(["a"]);
            return { send: () => true, close: () => {} };
        };
        // Query notifications go out on a zero-delay timer, so the clock has to move for React to see them.
        const tick = (ms: number) => act(() => void vi.advanceTimersByTime(ms));
        render(
            <DataProvider connect={connect}>
                <LiveStatus />
            </DataProvider>,
        );
        tick(0);
        expect(screen.getByText("updated 0s ago")).toBeTruthy();
        tick(3_000);
        expect(screen.getByText("updated 3s ago")).toBeTruthy();

        const button = screen.getByRole("button", { name: "Pause live updates" });
        fireEvent.click(button);
        tick(0);
        expect(screen.getByText("paused")).toBeTruthy();
        act(() => frame(["a", "b"]));
        tick(2_000);
        // Frames arrive but the age keeps counting from the snapshot on screen.
        expect(screen.getByText("updated 5s ago")).toBeTruthy();

        fireEvent.click(button);
        tick(0);
        expect(screen.queryByText("paused")).toBeNull();
        expect(screen.getByText("updated 0s ago")).toBeTruthy();
    });
});
