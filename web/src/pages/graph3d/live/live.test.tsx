// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, test } from "vitest";
import { richSnapshot } from "../../fixtures";
import { mount, wire } from "../../testHelpers";
import { batch, end, event } from "./callsFixtures";

afterEach(cleanup);

const waiting = () => screen.findByText("Waiting for graph calls");
const subscriptions = (sent: unknown[]) =>
    sent.flatMap((m) => (m && typeof m === "object" && "calls" in m ? [m.calls] : []));

describe("live graph calls", () => {
    test("it subscribes, shows the waiting state, then the call in the metrics and the feed", async () => {
        const w = wire(richSnapshot);
        mount(w.connect, "/graph");
        await waiting();
        await waitFor(() => expect(subscriptions(w.sent)).toEqual([{ subscribe: true }]));

        act(() => w.frame({ type: "calls", batch: batch([event({ call: "a" })]) }));
        await waitFor(() => expect(screen.queryByText("Waiting for graph calls")).toBeNull());
        const running = screen.getByRole("list", { name: "running calls" });
        expect(within(running).getByText("callers")).toBeTruthy();

        act(() => w.frame({ type: "calls", batch: batch([end("a", 1000, 250)]) }));
        const table = await screen.findByRole("table", { name: "graph calls" });
        await waitFor(() => expect(within(table).getByText(/750 saved/)).toBeTruthy());
        expect(screen.getByText("75%")).toBeTruthy();
    });

    test("a failed call is red in the feed", async () => {
        const w = wire(richSnapshot);
        mount(w.connect, "/graph");
        await waiting();
        const failed = end("a", 0, 0, { ok: false, error: "no backend" });
        act(() => w.frame({ type: "calls", batch: batch([failed]) }));
        expect((await screen.findByText("no backend")).className).toContain("text-delta-fg");
    });

    test("freeze holds the picture while the totals keep counting, and unfreeze catches up", async () => {
        const w = wire(richSnapshot);
        mount(w.connect, "/graph");
        await waiting();
        act(() =>
            w.frame({ type: "calls", batch: batch([end("a", 100, 50, { target: "alpha" })]) }),
        );
        await screen.findByText("alpha");

        fireEvent.click(screen.getByRole("button", { name: "Freeze" }));
        const more = [end("b", 100, 50, { target: "beta" }), end("c", 100, 50)];
        act(() => w.frame({ type: "calls", batch: batch(more) }));
        expect(await screen.findByText("2 held")).toBeTruthy();
        expect(screen.queryByText("beta")).toBeNull();

        fireEvent.click(screen.getByRole("button", { name: "Unfreeze" }));
        expect(await screen.findByText("beta")).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "since open" }));
        // Three calls of 100 tokens each, none dropped by the freeze.
        expect(await screen.findByText("300")).toBeTruthy();
    });

    test("the window selector recomputes the totals", async () => {
        const w = wire(richSnapshot);
        mount(w.connect, "/graph");
        await waiting();
        act(() => w.frame({ type: "calls", batch: batch([end("a", 10, 4)]) }));
        await screen.findByRole("table", { name: "graph calls" });
        for (const label of ["1 min", "5 min", "15 min", "since open"]) {
            fireEvent.click(screen.getByRole("button", { name: label }));
            const chip = screen.getByRole("button", { name: label });
            expect(chip.getAttribute("aria-pressed")).toBe("true");
        }
    });

    test("a 500-call burst is one frame whose totals count every call", async () => {
        const w = wire(richSnapshot);
        mount(w.connect, "/graph");
        await waiting();
        const listed = Array.from({ length: 100 }, (_, i) => end(`c${i}`, 10, 4));
        act(() => w.frame({ type: "calls", batch: batch(listed, 400) }));
        fireEvent.click(screen.getByRole("button", { name: "since open" }));
        await waitFor(() => expect(screen.getByText("500")).toBeTruthy());
    });
});
