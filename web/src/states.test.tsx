// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { Empty, ErrorState, Offline } from "./states";

afterEach(cleanup);

describe("shared states", () => {
    test("Empty is a status with the hint under the title", () => {
        render(<Empty title="No calls yet" hint="Run an agent." />);
        const status = screen.getByRole("status");
        expect(status.textContent).toContain("No calls yet");
        expect(status.textContent).toContain("Run an agent.");
    });

    test("ErrorState is an alert on the danger roles and shows the message", () => {
        render(<ErrorState message="store will not open" />);
        const alert = screen.getByRole("alert");
        expect(alert.textContent).toContain("store will not open");
        expect(alert.className).toContain("border-danger/50");
    });

    test("Offline reconnects once and then waits for the answer", () => {
        const reconnect = vi.fn();
        const { rerender } = render(<Offline connecting={false} onReconnect={reconnect} />);
        fireEvent.click(screen.getByRole("button", { name: "Reconnect" }));
        expect(reconnect).toHaveBeenCalledTimes(1);
        rerender(<Offline connecting onReconnect={reconnect} />);
        const busy = screen.getByRole("button", { name: "Reconnecting…" });
        expect((busy as HTMLButtonElement).disabled).toBe(true);
        expect(busy.getAttribute("aria-busy")).toBe("true");
    });
});
