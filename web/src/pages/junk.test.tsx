// @vitest-environment happy-dom
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, test } from "vitest";
import { sampleSnapshot } from "../api/sample";
import { mockJunk, samplePlanItems } from "../api/sampleJunk";
import { groupPlan, initialJunk, junkReducer, removable } from "./junkState";
import { parseHosts } from "./text";
import { mount, wire } from "./testHelpers";

afterEach(cleanup);

const junkMessages = (sent: unknown[]) =>
    sent.filter((m): m is { junk: { action: string; paths: string[] } } => "junk" in (m as object));

describe("junk state", () => {
    const plan = mockJunk().plan();

    test("walks plan, confirm and apply, and only confirming can apply", () => {
        let s = junkReducer(initialJunk, { type: "applying" });
        expect(s.phase).toBe("idle");
        s = junkReducer(initialJunk, { type: "plan" });
        s = junkReducer(s, { type: "ask" });
        expect(s.phase).toBe("planning");
        s = junkReducer(s, { type: "planned", plan });
        expect(s.phase).toBe("ready");
        expect(junkReducer(s, { type: "applying" }).phase).toBe("ready");
        s = junkReducer(s, { type: "ask" });
        expect(s.phase).toBe("confirming");
        s = junkReducer(s, { type: "applying" });
        s = junkReducer(s, { type: "applied", result: plan });
        expect(s).toMatchObject({ phase: "done", plan: null });
    });

    test("nothing removable cannot be confirmed, and a failure starts over", () => {
        const empty = { ...plan, items: [], planned_bytes: 0 };
        let s = junkReducer(junkReducer(initialJunk, { type: "plan" }), {
            type: "planned",
            plan: empty,
        });
        expect(junkReducer(s, { type: "ask" }).phase).toBe("ready");
        s = junkReducer(s, { type: "failed", error: "boom" });
        expect(s).toMatchObject({ phase: "error", plan: null, error: "boom" });
    });

    test("only planned clear items are removable; kept ones stay listed per kind", () => {
        expect(removable(plan).map((i) => i.path)).toEqual([
            "/home/u/.rtok/logs/rtok.log.7",
            "/home/u/.claude/cache",
        ]);
        const groups = groupPlan(samplePlanItems);
        expect(groups.map((g) => [g.agent, g.kind, g.items.length, g.bytes])).toEqual([
            ["rtok", "log", 1, 3 * 1024 * 1024],
            ["claude", "cache", 1, 310 * 1024 * 1024],
            ["claude", "temp", 1, 0],
        ]);
    });

    test("the hosts text drops the junk section the tui prints", () => {
        const view = parseHosts("CLI: Codex\n  app  -\n\njunk\nrtok\n  Freed by `clear`: 0 B\n");
        expect(view.blocks.map((b) => b.name)).toEqual(["Codex"]);
        expect(view.other).toEqual([]);
    });
});

describe("junk card", () => {
    test("shows each agent's kinds and the Freed lines", async () => {
        mount(wire(sampleSnapshot).connect, "/hosts");
        const claude = await screen.findByRole("region", { name: "junk of claude" });
        expect(within(claude).getByText(/1 item, 310.0 MB/)).toBeTruthy();
        expect(claude.textContent).toContain("Freed by clear: 310.0 MB");
        expect(screen.getByText(/total: 924.0 MB in folders/)).toBeTruthy();
    });

    test("measures first: no card yet says so", async () => {
        mount(wire({ ...sampleSnapshot, junk: null }).connect, "/hosts");
        const panel = await screen.findByRole("region", { name: "junk" });
        expect(panel.querySelector("[aria-busy]")).toBeTruthy();
    });

    test("plans without deleting and sends the delete only on the confirm", async () => {
        const w = wire(sampleSnapshot);
        mount(w.connect, "/hosts");
        const junk = within(await screen.findByRole("region", { name: "junk" }));

        fireEvent.click(junk.getByRole("button", { name: "Clear safe junk" }));
        expect(junkMessages(w.sent)).toEqual([{ junk: { action: "plan", paths: [] } }]);

        act(() => w.frame({ type: "junkplan", plan: mockJunk().plan() }));
        const plan = within(await junk.findByRole("region", { name: "junk plan" }));
        expect(plan.getByText(/claude temp/).closest("details")?.textContent).toContain(
            "kept: agent running",
        );

        fireEvent.click(junk.getByRole("button", { name: "Clear 2 items" }));
        expect(junk.getByText(/Delete 2 items/)).toBeTruthy();
        fireEvent.click(junk.getByRole("button", { name: "Cancel" }));
        expect(junkMessages(w.sent)).toHaveLength(1);

        fireEvent.click(junk.getByRole("button", { name: "Clear safe junk" }));
        act(() => w.frame({ type: "junkplan", plan: mockJunk().plan() }));
        fireEvent.click(await junk.findByRole("button", { name: "Clear 2 items" }));
        expect(junkMessages(w.sent)).toHaveLength(2);

        fireEvent.click(junk.getByRole("button", { name: "Confirm" }));
        const apply = junkMessages(w.sent).at(-1)!;
        expect(apply.junk.action).toBe("apply");
        // The kept item was never confirmed.
        expect(apply.junk.paths).toEqual([
            "/home/u/.rtok/logs/rtok.log.7",
            "/home/u/.claude/cache",
        ]);

        act(() =>
            w.frame({ type: "junkcleared", cleared: mockJunk().apply(apply.junk.paths) }),
        );
        await waitFor(() =>
            expect(junk.getByRole("status").textContent).toBe(
                "Freed 313.0 MB of 313.0 MB planned",
            ),
        );
    });

    test("an error from the server comes back to the first button", async () => {
        const w = wire(sampleSnapshot);
        mount(w.connect, "/hosts");
        const junk = within(await screen.findByRole("region", { name: "junk" }));
        fireEvent.click(junk.getByRole("button", { name: "Clear safe junk" }));
        act(() => w.message("junk needs an action"));
        expect((await junk.findByRole("alert")).textContent).toContain("junk needs an action");
        expect(junk.getByRole("button", { name: "Clear safe junk" })).toBeTruthy();
    });
});
