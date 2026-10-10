// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { TOAST_MS, Toasts } from "./Toasts";

afterEach(() => {
    cleanup();
    vi.useRealTimers();
});

describe("toasts", () => {
    test("a toast asks to be dismissed once its time is up, not before", () => {
        vi.useFakeTimers();
        const dismissed: number[] = [];
        render(
            <Toasts
                items={[{ id: 7, tone: "ok", text: "b recovered" }]}
                onDismiss={(id) => dismissed.push(id)}
            />,
        );
        expect(screen.getByText("b recovered")).toBeTruthy();
        act(() => void vi.advanceTimersByTime(TOAST_MS - 1));
        expect(dismissed).toEqual([]);
        act(() => void vi.advanceTimersByTime(1));
        expect(dismissed).toEqual([7]);
    });
});
