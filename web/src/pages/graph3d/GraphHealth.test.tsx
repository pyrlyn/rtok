// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import type { Snapshot } from "../../api/snapshot.gen";
import { richSnapshot } from "../fixtures";
import { mount, serving } from "../testHelpers";
import { healthRows } from "./healthFixtures";
import { HealthBreakdown, HealthRing } from "./HealthRing";
import { healthLabel, healthLines, levelOf } from "./health";
import { VIEW_KEY } from "./ProjectsOverview";
import { buildScene, nodeTip } from "./scene";

beforeEach(() => localStorage.clear());
afterEach(cleanup);

const [steady, lagging, fresh, gone] = healthRows();
const page = (projects = healthRows()) => {
    const snap: Snapshot = { ...richSnapshot, projects };
    return mount(serving(snap), "/graph");
};

describe("health helpers", () => {
    test("a bare score falls into the server's bands", () => {
        expect([100, 80, 79, 50, 49, 0].map(levelOf)).toEqual([
            "good",
            "good",
            "warn",
            "warn",
            "bad",
            "bad",
        ]);
    });

    test("the label is the score, or the reason there is none", () => {
        expect([steady, lagging, fresh, gone].map((p) => healthLabel(p!.health))).toEqual([
            "100",
            "60",
            "indexing",
            "missing",
        ]);
    });

    test("the tooltip lines carry the components and every reason with its fix", () => {
        expect(healthLines(lagging!.health)).toEqual([
            "health 60",
            "freshness 50% · backend 60% · links 73%",
            "3 files pending (30%); fix: run `rtok graph index /work/lagging`",
            "rust-analyzer is not installed; fix: install the server; it is picked up within one health-check interval, or restart",
        ]);
    });

    test("a node's tip follows its name with the breakdown", () => {
        const tip = nodeTip(buildScene(healthRows(), { query: "", scopeOnly: false }).nodes[1]!);
        expect(tip.split("\n")[0]).toBe("lagging · stale");
        expect(tip).toMatch(/health 60\nfreshness 50%/);
    });
});

describe("health ring", () => {
    test("each level has its own name, colour role and text", () => {
        const { container } = render(
            <>
                {[steady, lagging, fresh, gone].map((p) => (
                    <HealthRing key={p!.id} health={p!.health} />
                ))}
            </>,
        );
        const rings = within(container).getAllByRole("img");
        expect(rings.map((r) => r.getAttribute("aria-label"))).toEqual([
            "health 100",
            "health 60",
            "health indexing",
            "health missing",
        ]);
        expect(rings.map((r) => r.getAttribute("data-level"))).toEqual([
            "good",
            "warn",
            "indexing",
            "missing",
        ]);
        // The arc is the colour; the grey ring of a first index is dashed, not a share of a score.
        const arcs = rings.map((r) => r.querySelectorAll("circle")[1]!);
        expect(arcs.map((a) => a.style.stroke)).toEqual([
            "var(--pyr-success-fg)",
            "var(--pyr-warn-fg)",
            "var(--pyr-fg-subtle)",
            "var(--pyr-danger-fg)",
        ]);
        expect(arcs[2]!.getAttribute("stroke-dasharray")).toBe("3 3");
    });

    test("the breakdown lists the components and each reason's fix", () => {
        render(<HealthBreakdown health={lagging!.health} />);
        const box = screen.getByLabelText("health breakdown");
        expect(box.textContent).toMatch(/freshness 50% · backend 60% · links 73%/);
        expect(within(box).getAllByRole("listitem")).toHaveLength(2);
        expect(box.textContent).toMatch(/fix: run `rtok graph index/);
    });
});

describe("health on the graph page", () => {
    test("the list shows a ring and the breakdown for every project", async () => {
        localStorage.setItem(VIEW_KEY, "list");
        page();
        const list = await screen.findByRole("list", { name: "project graph" });
        expect(
            within(list)
                .getAllByRole("img")
                .map((r) => r.getAttribute("data-level")),
        ).toEqual(["good", "warn", "indexing", "missing"]);
        expect(within(list).getAllByLabelText("health breakdown")).toHaveLength(4);
        expect(list.textContent).toMatch(/the directory no longer exists/);
    });

    test("the 2D view rings every node and names the score on it", async () => {
        page();
        await screen.findByText(/3D view unavailable/);
        const rings = await screen.findAllByTestId("health-2d");
        expect(rings.map((r) => r.getAttribute("data-level"))).toEqual([
            "good",
            "warn",
            "indexing",
            "missing",
        ]);
        expect(screen.getByRole("button", { name: /lagging, stale, health 60/ })).toBeTruthy();
    });

    test("the selected project shows its ring, breakdown and the scope's lowest score", async () => {
        page();
        const current = within(await screen.findByLabelText("current project"));
        expect(current.getByLabelText("health 100")).toBeTruthy();
        expect(current.getByLabelText("health breakdown").textContent).toMatch(/links 100%/);
        expect(current.getByText("scope 60")).toBeTruthy();
    });

    test("no scope score while every member is on its first index", async () => {
        page([{ ...steady!, scope_health: null }]);
        const current = within(await screen.findByLabelText("current project"));
        expect(current.queryByText(/^scope/)).toBeNull();
    });

    test("the selector marks each project with its ring", async () => {
        page();
        const list = await screen.findByRole("list", { name: "projects" });
        expect(within(list).getByLabelText("health indexing")).toBeTruthy();
        expect(within(list).getByLabelText("health 60")).toBeTruthy();
    });
});
