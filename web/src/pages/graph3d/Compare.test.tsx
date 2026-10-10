// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { drillServer } from "../../api/sampleDrill";
import { project } from "../../api/sampleRows";
import type { ClientMessage, DiffRequest, Snapshot } from "../../api/snapshot.gen";
import { richSnapshot } from "../fixtures";
import { mountRouted } from "../testHelpers";
import { VIEW_KEY } from "./ProjectsOverview";

beforeEach(() => localStorage.setItem(VIEW_KEY, "list"));
afterEach(cleanup);

const snapshot: Snapshot = {
    ...richSnapshot,
    projects: [
        project(1, "rtok", {
            selected: true,
            links: [{ to: 2, kind: "manual", name: "ketch", reason: null }],
        }),
        project(2, "ketch"),
    ],
};

/** The sample server, remembering the diff requests it was asked. */
function serve() {
    const asked: DiffRequest[] = [];
    const sent: ClientMessage[] = [];
    const connect: typeof drillServer extends (s: Snapshot) => infer C ? C : never = (h) => {
        const inner = drillServer(snapshot)(h);
        return {
            ...inner,
            send: (m) => (sent.push(m), "diff" in m && asked.push(m.diff), inner.send(m)),
        };
    };
    return { connect, asked, sent };
}

const EXPANDED = "/graph?p=1&x=%5B%22src%2Fplugins%2Fgraph%2Fdrill.rs%22%2C%22src%2Fhook.rs%22%5D";
const compare = () => screen.findByRole("button", { name: "Compare" });
const panel = () => screen.findByRole("region", { name: "compare" });

describe("Compare mode", () => {
    test("is off until asked, and then asks the server for the diff against HEAD", async () => {
        const { connect, asked } = serve();
        mountRouted(connect, EXPANDED);
        const toggle = await compare();
        expect(toggle.getAttribute("aria-pressed")).toBe("false");
        expect(screen.queryByRole("region", { name: "compare" })).toBeNull();
        expect(asked).toHaveLength(0);

        fireEvent.click(toggle);
        const side = within(await panel());
        expect(await side.findByText("~ 2 changed")).toBeTruthy();
        expect(asked).toEqual([{ project: "1", from: [], to: null, export: null }]);
        expect(side.getByText("+ 1 added")).toBeTruthy();
        expect(side.getByText("− 1 removed")).toBeTruthy();
        expect(side.getByText("→ 1 moved")).toBeTruthy();
    });

    test("lists what changed with its callers, the edges and the files no grammar read", async () => {
        const { connect } = serve();
        mountRouted(connect, EXPANDED);
        fireEvent.click(await compare());
        const side = within(await panel());
        const changed = within(await side.findByRole("region", { name: "changed" }));
        expect(changed.getByText("run")).toBeTruthy();
        expect(changed.getByText("signature")).toBeTruthy();
        expect(changed.getByText("body")).toBeTruthy();
        expect(changed.getByRole("list", { name: "callers of run" }).textContent).toContain(
            "main src/main.rs d1",
        );
        expect(side.getByRole("region", { name: "edges added" }).textContent).toContain(
            "run → resolve",
        );
        expect(side.getByRole("region", { name: "edges removed" }).textContent).toContain(
            "run_hook → legacy_hook",
        );
        expect(side.getByRole("region", { name: "changed, not analysed" }).textContent).toContain(
            "docs/guide.md",
        );
    });

    test("names each change on the node as well as colouring it", async () => {
        const { connect } = serve();
        mountRouted(connect, EXPANDED);
        fireEvent.click(await compare());
        const run = await screen.findByRole("button", { name: /^run.*changed/ });
        expect(run).toBeTruthy();
        expect(screen.getByRole("button", { name: /^resolve.*added/ })).toBeTruthy();
        // The removed function is not in the working tree: a ghost stands in its file.
        expect(screen.getByRole("button", { name: /^legacy_hook.*removed/ })).toBeTruthy();
        // Turning it off puts the live picture back.
        fireEvent.click(screen.getByRole("button", { name: "Compare" }));
        await waitFor(() =>
            expect(screen.queryByRole("button", { name: /^legacy_hook/ })).toBeNull(),
        );
        expect(screen.queryByRole("region", { name: "compare" })).toBeNull();
    });

    test("a typed ref is sent on Enter, and PROJECT:REF reaches the server as typed", async () => {
        const { connect, asked } = serve();
        mountRouted(connect, EXPANDED);
        fireEvent.click(await compare());
        const input = await screen.findByLabelText("compare with");
        fireEvent.change(input, { target: { value: "ketch:v1" } });
        expect(asked).toHaveLength(1);
        fireEvent.keyDown(input, { key: "Enter" });
        await waitFor(() => expect(asked.at(-1)?.from).toEqual(["ketch:v1"]));
    });

    test("a saved export goes up as its text and replaces the ref, never as a path", async () => {
        const { connect, asked } = serve();
        mountRouted(connect, EXPANDED);
        fireEvent.click(await compare());
        const file = new File(['{"schema":"rtok.graph.v1"}'], "before.json", {
            type: "application/json",
        });
        fireEvent.change(await screen.findByLabelText("saved export"), {
            target: { files: [file] },
        });
        await waitFor(() =>
            expect(asked.at(-1)).toEqual({
                project: "1",
                from: [],
                to: null,
                export: { name: "before.json", text: '{"schema":"rtok.graph.v1"}' },
            }),
        );
        const side = within(await panel());
        expect(await side.findByText(/export before\.json/)).toBeTruthy();
        // An export has no signatures and no edges: the server says so and lists none.
        expect(side.getByText(/only added and removed symbols/)).toBeTruthy();
        expect(side.queryByRole("region", { name: "changed" })).toBeNull();
        expect(side.getByRole("region", { name: "links added" }).textContent).toContain(
            "rtok → ketch",
        );

        fireEvent.click(side.getByRole("button", { name: "Use a ref" }));
        // The ref answer is still cached from the first ask, so it comes back without a request.
        expect(await within(await panel()).findByRole("region", { name: "changed" })).toBeTruthy();
        expect(screen.queryByText(/export before\.json/)).toBeNull();
    });

    test("a file that is not an export, or is too large, is refused with the reason", async () => {
        const { connect, asked } = serve();
        mountRouted(connect, EXPANDED);
        fireEvent.click(await compare());
        const input = await screen.findByLabelText("saved export");
        fireEvent.change(input, {
            target: { files: [new File(["not json"], "notes.txt")] },
        });
        const alert = await screen.findByRole("alert");
        expect(alert.textContent).toContain("notes.txt is not a graph export");

        const big = new File(["x"], "huge.json");
        Object.defineProperty(big, "size", { value: 17 << 20 });
        const before = asked.length;
        fireEvent.change(input, { target: { files: [big] } });
        expect((await screen.findAllByRole("alert")).map((a) => a.textContent).join()).toContain(
            "huge.json is larger than 16 MiB",
        );
        expect(asked).toHaveLength(before);
    });

    test("the live graph keeps asking and drawing while Compare is on", async () => {
        const { connect, sent } = serve();
        mountRouted(connect, EXPANDED);
        fireEvent.click(await compare());
        await panel();
        // The live section subscribes to the call stream independently of Compare.
        await waitFor(() => expect(sent.some((m) => "graph" in m)).toBe(true));
        expect(await screen.findByRole("button", { name: /^run.*changed/ })).toBeTruthy();
        expect(screen.getByRole("heading", { name: /live/i })).toBeTruthy();
    });
});
