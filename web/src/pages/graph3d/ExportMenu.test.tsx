// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { drillServer } from "../../api/sampleDrill";
import { project } from "../../api/sampleRows";
import type { ClientMessage, Snapshot } from "../../api/snapshot.gen";
import { richSnapshot } from "../fixtures";
import { mountRouted } from "../testHelpers";
import { exportRequest } from "./ExportMenu";
import { VIEW_KEY } from "./ProjectsOverview";

let blobs: Blob[];

beforeEach(() => {
    localStorage.setItem(VIEW_KEY, "list");
    blobs = [];
    URL.createObjectURL = (b: Blob | MediaSource) => {
        blobs.push(b as Blob);
        return "blob:test";
    };
    URL.revokeObjectURL = () => {};
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
});
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

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

/** The sample server, remembering what the page sent. */
function serve() {
    const sent: ClientMessage[] = [];
    const connect: ReturnType<typeof drillServer> = (h) => {
        const inner = drillServer(snapshot)(h);
        return { ...inner, send: (m) => (sent.push(m), inner.send(m)) };
    };
    return { connect, sent };
}

const FOCUSED = "/graph?p=1&fp=src%2Fhook.rs&fn=run_hook&d=2";
const menu = async () => {
    fireEvent.click(await screen.findByRole("button", { name: "Export" }));
    return within(screen.getByRole("group", { name: "export options" }));
};

describe("exportRequest", () => {
    const png = { format: "png", scale: 2, transparent: true } as const;
    const drill = {
        project: "1",
        expand: [],
        focus: { path: "src/hook.rs", name: "run_hook" },
        depth: 3,
    };

    test("the overview asks for its level only, a focus carries the symbol and its depth", () => {
        expect(exportRequest("overview", "1", null, png)).toEqual({
            project: "1",
            level: "overview",
            focus: null,
            depth: null,
            format: "png",
            scale: 2,
            transparent: true,
        });
        expect(exportRequest("project", "1", drill, png)).toMatchObject({
            level: "symbols",
            focus: null,
        });
        expect(exportRequest("focus", "1", drill, png)).toMatchObject({
            level: "symbols",
            focus: "run_hook",
            depth: 3,
        });
    });

    test("scale belongs to PNG and a background only to a picture", () => {
        const json = { format: "json", scale: 4, transparent: true } as const;
        expect(exportRequest("overview", "1", null, json)).toMatchObject({
            scale: null,
            transparent: false,
        });
        const svg = { format: "svg", scale: 4, transparent: true } as const;
        expect(exportRequest("overview", "1", null, svg)).toMatchObject({
            scale: null,
            transparent: true,
        });
    });
});

describe("Export menu", () => {
    test("says that file and symbol names are included before anything is sent", async () => {
        const { connect, sent } = serve();
        mountRouted(connect, "/graph");
        const options = await menu();
        expect(options.getByText(/File names and symbol names are included/)).toBeTruthy();
        expect(options.getByText(/source text never is/)).toBeTruthy();
        expect(sent.filter((m) => "export" in m)).toHaveLength(0);
    });

    test("offers the overview alone on the overview and the drill-down choices inside a project", async () => {
        const first = serve();
        mountRouted(first.connect, "/graph");
        const overview = await menu();
        expect(overview.getAllByRole("radio")).toHaveLength(1);
        cleanup();

        const second = serve();
        mountRouted(second.connect, FOCUSED);
        const drilled = await menu();
        expect(drilled.getAllByRole("radio").map((r) => r.parentElement?.textContent)).toEqual([
            "Overview of rtok: its projects and links",
            "Symbol graph of rtok",
            "Subgraph around run_hook, 2 calls deep",
        ]);
    });

    test("downloads the file the server made, byte for byte", async () => {
        const { connect, sent } = serve();
        mountRouted(connect, FOCUSED);
        const options = await menu();
        fireEvent.click(options.getByRole("radio", { name: /Subgraph around run_hook/ }));
        fireEvent.click(options.getByRole("button", { name: "Download" }));
        await waitFor(() => expect(blobs).toHaveLength(1));
        expect(sent.find((m) => "export" in m)).toEqual({
            export: {
                project: "1",
                level: "symbols",
                focus: "run_hook",
                depth: 2,
                format: "json",
                scale: null,
                transparent: false,
            },
        });
        const doc = JSON.parse(await blobs[0]!.text()) as {
            schema: string;
            meta: { level: string };
        };
        expect(doc.schema).toBe("rtok.graph.v1");
        expect(doc.meta.level).toBe("focus");
        expect(blobs[0]!.type).toBe("application/json");
    });

    test("a PNG asks for its size and arrives as bytes with its type", async () => {
        const { connect, sent } = serve();
        mountRouted(connect, "/graph");
        const options = await menu();
        fireEvent.change(options.getByLabelText("format"), { target: { value: "png" } });
        fireEvent.change(options.getByLabelText("size"), { target: { value: "4" } });
        expect(options.getByText(/200 best connected nodes/)).toBeTruthy();
        fireEvent.click(options.getByRole("button", { name: "Download" }));
        await waitFor(() => expect(blobs).toHaveLength(1));
        expect(sent.find((m) => "export" in m)).toMatchObject({
            export: { format: "png", scale: 4, level: "overview" },
        });
        expect(blobs[0]!.type).toBe("image/png");
    });
});

describe("Opening an export", () => {
    const saved = JSON.stringify({
        schema: "rtok.graph.v1",
        projects: [
            {
                id: 1,
                name: "rtok",
                root: "~/rtok",
                origin: "manual",
                backend: "tags",
                health: "ok",
            },
            {
                id: 2,
                name: "ketch",
                root: "~/ketch",
                origin: "manual",
                backend: "tags",
                health: "ok",
            },
        ],
        links: [{ from: 1, to: 2, kind: "manual", references: 3 }],
        nodes: [
            { id: "1:a.rs:1:f", project: 1, kind: "function", name: "f", path: "a.rs", line: 1 },
        ],
        edges: [],
        meta: {
            scope: ["rtok", "ketch"],
            level: "symbols",
            exported_at: 0,
            rtok_version: "0.15.1",
            redacted: true,
            partial: false,
            notes: [],
        },
    });
    const file = (text: string, name = "mine.json") => new File([text], name);

    test("sends the text, shows it behind the banner and has nothing to write with", async () => {
        const { connect, sent } = serve();
        mountRouted(connect, "/graph");
        fireEvent.change(await screen.findByLabelText("open an export"), {
            target: { files: [file(saved)] },
        });
        expect(await screen.findByText("viewing export from mine.json")).toBeTruthy();
        expect(sent.find((m) => "import" in m)).toEqual({
            import: { name: "mine.json", text: saved },
        });
        expect(screen.getByText("rtok → ketch")).toBeTruthy();
        expect(screen.getByRole("region", { name: "symbols" }).textContent).toContain("a.rs:1");
        // Read-only: the live pictures and their controls are gone; only Close is offered.
        expect(screen.queryByRole("button", { name: "Compare" })).toBeNull();
        expect(sent.filter((m) => "project" in m || "set" in m)).toHaveLength(0);

        fireEvent.click(screen.getByRole("button", { name: "Close export" }));
        await waitFor(() => expect(screen.queryByText(/viewing export from/)).toBeNull());
    });

    test("a file that is not an export is refused with the server's words", async () => {
        const { connect } = serve();
        mountRouted(connect, "/graph");
        fireEvent.change(await screen.findByLabelText("open an export"), {
            target: { files: [file("not json", "notes.json")] },
        });
        expect((await screen.findByRole("alert")).textContent).toContain(
            "notes.json is not a graph export",
        );
        expect(screen.queryByText(/viewing export from/)).toBeNull();
    });
});
