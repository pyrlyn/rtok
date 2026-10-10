// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { BIG, drillReply, drillServer } from "../../api/sampleDrill";
import { project } from "../../api/sampleRows";
import type { ClientMessage, DrillRequest, ProjectRow, Snapshot } from "../../api/snapshot.gen";
import { richSnapshot } from "../fixtures";
import { mountRouted, wire } from "../testHelpers";
import { VIEW_KEY } from "./ProjectsOverview";

beforeEach(() => localStorage.setItem(VIEW_KEY, "list"));
afterEach(cleanup);

const rows = (over: Record<number, Partial<ProjectRow>> = {}): ProjectRow[] => [
    project(1, "rtok", {
        selected: true,
        links: [{ to: 2, kind: "manual", name: "ketch", reason: null }],
        ...over[1],
    }),
    project(2, "ketch", over[2]),
    project(3, BIG, over[3]),
    project(4, "gone", { missing: true, state: "missing", index: null, ...over[4] }),
    project(5, "fresh", { index: null, state: "stale", ...over[5] }),
];
const snapshot = (projects = rows()): Snapshot => ({ ...richSnapshot, projects });

/** A server that answers every graph request and remembers what it was asked. */
function serve(snap = snapshot()) {
    const asked: DrillRequest[] = [];
    const connect: typeof drillServer extends (s: Snapshot) => infer C ? C : never = (h) => {
        const inner = drillServer(snap)(h);
        return {
            ...inner,
            send: (m) => ("graph" in m && asked.push(m.graph), inner.send(m)),
        };
    };
    return { connect, asked };
}

/** The graph requests a `wire` recorded, in order. */
const graphs = (sent: ClientMessage[]) => sent.flatMap((m) => ("graph" in m ? [m.graph] : []));
const list = () => screen.findByRole("list", { name: "symbol graph" });
const items = async () =>
    within(await list())
        .getAllByRole("button")
        .map((b) => b.querySelector("b")!.textContent)
        .sort();
const search = (router: { state: { location: { search: unknown } } }) =>
    router.state.location.search as Record<string, unknown>;

describe("drill-down page", () => {
    test("a project in the URL opens at its files, under a breadcrumb", async () => {
        const { connect, asked } = serve();
        mountRouted(connect, "/graph?p=1");
        // The call into ketch ends at an external node even while store.rs is collapsed.
        expect(await items()).toEqual(["drill.rs", "hook.rs", "main.rs", "open_index", "store.rs"]);
        expect(asked[0]).toMatchObject({ project: "1", expand: [], focus: null, depth: null });
        const crumbs = within(screen.getByRole("navigation", { name: "breadcrumb" }));
        expect(crumbs.getByText("All projects")).toBeTruthy();
        expect(crumbs.getByText("rtok").getAttribute("aria-current")).toBe("page");
    });

    test("a second click on a file expands it, and Back returns", async () => {
        const { connect, asked } = serve();
        const { router } = mountRouted(connect, "/graph?p=1");
        await items();
        const file = () => screen.getByRole("button", { name: /drill\.rs/ });
        fireEvent.click(file());
        expect(file().getAttribute("aria-pressed")).toBe("true");
        expect(asked).toHaveLength(1);
        fireEvent.click(file());
        await waitFor(() => expect(search(router).x).toEqual(["src/plugins/graph/drill.rs"]));
        expect(await screen.findByRole("button", { name: /^run/ })).toBeTruthy();
        expect(asked.at(-1)!.expand).toEqual(["src/plugins/graph/drill.rs"]);
        const here = within(screen.getByRole("navigation", { name: "breadcrumb" }));
        expect(here.getByText("src/plugins/graph/drill.rs")).toBeTruthy();

        act(() => router.history.back());
        await waitFor(() => expect(search(router).x).toBeUndefined());
        await waitFor(() => expect(screen.queryByRole("button", { name: /^run/ })).toBeNull());
        expect(search(router).p).toBe("1");
    });

    test("a second click on a function focuses it, and the depth chips widen it", async () => {
        const { connect, asked } = serve();
        const { router } = mountRouted(
            connect,
            "/graph?p=1&x=%5B%22src%2Fplugins%2Fgraph%2Fdrill.rs%22%5D",
        );
        const run = await screen.findByRole("button", { name: /^run/ });
        fireEvent.click(run);
        fireEvent.click(run);
        await waitFor(() =>
            expect(search(router)).toMatchObject({ fp: "src/plugins/graph/drill.rs", fn: "run" }),
        );
        fireEvent.click(await screen.findByRole("button", { name: "depth 2" }));
        await waitFor(() => expect(search(router).d).toBe(2));
        await waitFor(() =>
            expect(asked.at(-1)).toMatchObject({ depth: 2, focus: { name: "run" } }),
        );
    });

    test("an external node opens the linked project with the symbol focused", async () => {
        const { connect, asked } = serve();
        const { router } = mountRouted(connect, "/graph?p=1&x=%5B%22src%2Fstore.rs%22%5D");
        fireEvent.click(await screen.findByRole("button", { name: /^open_index/ }));
        await waitFor(() =>
            expect(search(router)).toMatchObject({ p: "2", fp: "src/lib.rs", fn: "open_index" }),
        );
        expect(search(router).x).toBeUndefined();
        await waitFor(() =>
            expect(asked.at(-1)).toMatchObject({ project: "2", focus: { name: "open_index" } }),
        );
        const crumbs = within(await screen.findByRole("navigation", { name: "breadcrumb" }));
        expect(crumbs.getByText("ketch")).toBeTruthy();
    });

    test("the project crumb and All projects lead back up", async () => {
        const { connect } = serve();
        const { router } = mountRouted(connect, "/graph?p=1&x=%5B%22src%2Fstore.rs%22%5D");
        await screen.findByRole("button", { name: /^open_store/ });
        fireEvent.click(screen.getByRole("button", { name: "rtok" }));
        await waitFor(() => expect(search(router).x).toBeUndefined());
        expect(search(router).p).toBe("1");
        fireEvent.click(await screen.findByRole("button", { name: "All projects" }));
        await waitFor(() => expect(search(router).p).toBeUndefined());
        expect(await screen.findByRole("group", { name: "view" })).toBeTruthy();
        expect(screen.queryByRole("navigation", { name: "breadcrumb" })).toBeNull();
    });

    test("+N more raises the cap by 500 and goes away when everything is shown", async () => {
        const { connect, asked } = serve();
        mountRouted(connect, "/graph?p=3");
        const more = await screen.findByText("+100 more");
        expect(asked[0]!.limit).toBe(500);
        fireEvent.click(more);
        // 600 buttons in happy-dom take a while to commit.
        await waitFor(() => expect(screen.queryByText("+100 more")).toBeNull(), {
            timeout: 10_000,
        });
        expect(asked.at(-1)!.limit).toBe(1000);
        expect((await list()).querySelectorAll("li")).toHaveLength(600);
    }, 20_000);

    test("an unindexed project says how to index it, and a missing one is hollow", async () => {
        const first = serve();
        const a = mountRouted(first.connect, "/graph?p=5");
        expect(
            (await screen.findByText("Not indexed yet")).closest("[role=status]")!.textContent,
        ).toMatch(/rtok graph index --project 5/);
        a.view.unmount();
        const second = serve();
        mountRouted(second.connect, "/graph?p=4");
        expect(await screen.findByText("The directory is missing")).toBeTruthy();
        expect(screen.queryByRole("group", { name: "view" })).toBeNull();
    });

    test("pending files give a partial banner and a refusal gives an error", async () => {
        const partial = snapshot(
            rows({ 1: { index: { files: 4, indexed_at: 1, pending: 2, rows: 9, watch: "on" } } }),
        );
        mountRouted(serve(partial).connect, "/graph?p=1");
        expect((await screen.findByText(/Partial: some files changed/)).textContent).toBeTruthy();
        cleanup();
        mountRouted(serve().connect, "/graph?p=99");
        expect((await screen.findByRole("alert")).textContent).toMatch(/unknown project 99/);
    });

    test("a first read shows a spinner until the frame lands", async () => {
        const w = wire(snapshot());
        mountRouted(w.connect, "/graph?p=1");
        const status = await screen.findByText("Reading the index…");
        expect(status.getAttribute("aria-busy")).toBe("true");
        act(() => w.frame(drillReply(graphs(w.sent)[0]!, snapshot().projects!)));
        expect(await list()).toBeTruthy();
        expect(screen.queryByText("Reading the index…")).toBeNull();
    });

    test("the page asks again when the index numbers move, keeping the old picture meanwhile", async () => {
        const w = wire(snapshot());
        mountRouted(w.connect, "/graph?p=1");
        const asks = () => graphs(w.sent).length;
        await waitFor(() => expect(asks()).toBe(1));
        act(() => w.frame(drillReply(graphs(w.sent)[0]!, snapshot().projects!)));
        await list();

        const moved = snapshot(
            rows({ 1: { index: { files: 5, indexed_at: 2, pending: 0, rows: 400, watch: "on" } } }),
        );
        act(() => w.push(moved));
        await waitFor(() => expect(asks()).toBe(2));
        // The first frame is still on screen while the second is in flight.
        expect(screen.getByRole("button", { name: /main\.rs/ })).toBeTruthy();
        expect(screen.getByText("Updating…")).toBeTruthy();

        act(() => w.push({ ...moved, calls: moved.calls }));
        expect(asks()).toBe(2);
    });
});

const DRILL_RS = "/graph?p=1&x=%5B%22src%2Fplugins%2Fgraph%2Fdrill.rs%22%5D";
const panel = () => within(screen.getByRole("complementary", { name: "node details" }));
const searchBox = () => screen.getByRole("searchbox", { name: "search symbols" });
const submit = (text: string) => {
    fireEvent.change(searchBox(), { target: { value: text } });
    fireEvent.keyDown(searchBox(), { key: "Enter" });
};

describe("side panel", () => {
    test("a selected function shows its place, signature, callers, callees and editor link", async () => {
        const { connect } = serve();
        mountRouted(connect, DRILL_RS);
        expect(await screen.findByText("Select a node to see its details.")).toBeTruthy();
        fireEvent.click(await screen.findByRole("button", { name: /^run/ }));

        const p = panel();
        expect(p.getByText("run")).toBeTruthy();
        expect(p.getByText("src/plugins/graph/drill.rs", { selector: "p" })).toBeTruthy();
        expect(p.getByText(":40")).toBeTruthy();
        expect(p.getByText("fn run()")).toBeTruthy();
        expect(p.getByRole("link", { name: "Open in editor" }).getAttribute("href")).toBe(
            "vscode://file/work/rtok/src/plugins/graph/drill.rs:40",
        );
        const callees = within(p.getByRole("region", { name: "callees" }));
        expect(
            callees.getAllByRole("button").map((b) => b.querySelector("b")!.textContent),
        ).toEqual(["load", "resolve"]);
        expect(p.getByRole("region", { name: "callers" }).textContent).toMatch(
            /none in this picture/,
        );

        // A callee is one click away, and its callers name the function we came from.
        fireEvent.click(callees.getByRole("button", { name: /^load/ }));
        const callers = within(panel().getByRole("region", { name: "callers" }));
        expect(callers.getByRole("button", { name: /^run/ })).toBeTruthy();
    });

    test("a node of a linked project is linked under that project's root and can be opened", async () => {
        const { connect } = serve();
        const { router } = mountRouted(connect, "/graph?p=1&x=%5B%22src%2Fstore.rs%22%5D");
        fireEvent.click(await screen.findByRole("button", { name: /^open_store/ }));
        const callees = within(panel().getByRole("region", { name: "callees" }));
        fireEvent.click(callees.getByRole("button", { name: /^open_index/ }));

        expect(panel().getByText("ketch:")).toBeTruthy();
        expect(panel().getByRole("link", { name: "Open in editor" }).getAttribute("href")).toBe(
            "vscode://file/work/ketch/src/lib.rs:7",
        );
        fireEvent.click(panel().getByRole("button", { name: "Open in ketch" }));
        await waitFor(() => expect(search(router)).toMatchObject({ p: "2", fn: "open_index" }));
    });

    test("a file has an editor link without a line", async () => {
        const { connect } = serve();
        mountRouted(connect, "/graph?p=1");
        fireEvent.click(await screen.findByRole("button", { name: /^main\.rs/ }));
        expect(panel().getByRole("link", { name: "Open in editor" }).getAttribute("href")).toBe(
            "vscode://file/work/rtok/src/main.rs",
        );
    });
});

describe("search", () => {
    test("Enter sends the text as the request's query; typing alone sends nothing", async () => {
        const { connect, asked } = serve();
        mountRouted(connect, DRILL_RS);
        await items();
        fireEvent.change(searchBox(), { target: { value: "open" } });
        expect(asked).toHaveLength(1);
        expect(asked[0]!.query).toBe("");
        fireEvent.keyDown(searchBox(), { key: "Enter" });
        await waitFor(() => expect(asked.at(-1)!.query).toBe("open"));
    });

    test("hits name their projects, and one in another project opens it with the symbol focused", async () => {
        const { connect, asked } = serve();
        const { router } = mountRouted(connect, DRILL_RS);
        await items();
        submit("open");
        const hits = within(await screen.findByRole("list", { name: "search hits" }));
        expect(hits.getAllByRole("button").map((b) => b.querySelector("b")!.textContent)).toEqual([
            "open_store",
            "open_index",
        ]);
        expect(hits.getByText("ketch")).toBeTruthy();
        expect(hits.getByText("rtok")).toBeTruthy();

        fireEvent.click(hits.getByRole("button", { name: /^open_index/ }));
        await waitFor(() =>
            expect(search(router)).toMatchObject({ p: "2", fp: "src/lib.rs", fn: "open_index" }),
        );
        await waitFor(() => expect(asked.at(-1)).toMatchObject({ project: "2", query: "" }));
        // The new project's panel follows the focus without a click.
        expect(
            await within(
                await screen.findByRole("complementary", { name: "node details" }),
            ).findByText("fn open_index()"),
        ).toBeTruthy();
    });

    test("a hit in this project focuses its symbol and keeps the results", async () => {
        const { connect } = serve();
        const { router } = mountRouted(connect, "/graph?p=1");
        await items();
        submit("load");
        const hits = within(await screen.findByRole("list", { name: "search hits" }));
        fireEvent.click(hits.getByRole("button", { name: /^load/ }));
        await waitFor(() =>
            expect(search(router)).toMatchObject({
                p: "1",
                fp: "src/plugins/graph/drill.rs",
                fn: "load",
            }),
        );
        expect(await panel().findByText("fn load()")).toBeTruthy();
        expect(panel().getByRole("list", { name: "search hits" })).toBeTruthy();
        expect(panel().getByRole("button", { name: /^run/ })).toBeTruthy();
    });

    test("a search with no match says so, and an empty one clears the results", async () => {
        const { connect } = serve();
        mountRouted(connect, "/graph?p=1");
        await items();
        submit("zzz");
        expect(await panel().findByText("No symbol matches.")).toBeTruthy();
        submit("");
        await waitFor(() => expect(screen.queryByText("No symbol matches.")).toBeNull());
    });
});

describe("opening a project from the overview", () => {
    test("the node menu has Open, and it lands in the drill-down", async () => {
        localStorage.setItem(VIEW_KEY, "2d");
        const { connect } = serve();
        const { router } = mountRouted(connect, "/graph");
        const nodes = await screen.findAllByTestId("node-2d");
        fireEvent.contextMenu(nodes[0]!);
        fireEvent.click(await screen.findByRole("menuitem", { name: "Open" }));
        await waitFor(() => expect(search(router).p).toBe("1"));
        expect(await screen.findByRole("navigation", { name: "breadcrumb" })).toBeTruthy();
    });

    test("a double-click opens it, and a missing project cannot be opened", async () => {
        localStorage.setItem(VIEW_KEY, "2d");
        const { connect } = serve();
        const { router } = mountRouted(connect, "/graph");
        const nodes = await screen.findAllByTestId("node-2d");
        const gone = nodes.find((n) => n.getAttribute("aria-label")!.startsWith("gone"))!;
        fireEvent.doubleClick(gone);
        expect(search(router).p).toBeUndefined();
        fireEvent.contextMenu(gone);
        expect(
            (await screen.findByRole("menuitem", { name: "Open" })).hasAttribute("disabled"),
        ).toBe(true);
        fireEvent.keyDown(document, { key: "Escape" });
        fireEvent.doubleClick(
            nodes.find((n) => n.getAttribute("aria-label")!.startsWith("ketch"))!,
        );
        await waitFor(() => expect(search(router).p).toBe("2"));
    });
});
