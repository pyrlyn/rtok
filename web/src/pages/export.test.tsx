// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { richSnapshot } from "./fixtures";
import { mount, serving } from "./testHelpers";

let blobs: Blob[];

beforeEach(() => {
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

const lines = async () => (await blobs[0]?.text())?.trimEnd().split("\r\n");

describe("table export", () => {
    test("calls: the file holds the filtered, sorted rows and only the shown columns", async () => {
        mount(serving(richSnapshot), "/calls?surface=mcp&sort=-ms");
        fireEvent.click(await screen.findByRole("button", { name: "Export calls as CSV" }));
        expect(await lines()).toEqual([
            "time,surface,name,ms,tokens,ok",
            expect.stringMatching(/^\d{4}-.*,mcp,search,41,.*,true$/),
            expect.stringMatching(/,mcp,read,12,/),
            expect.stringMatching(/,mcp,outline,9,/),
        ]);
    });

    test("calls: a filter that matches nothing leaves the buttons disabled", async () => {
        mount(serving(richSnapshot), "/calls?q=no-such-call");
        const csv = await screen.findByRole("button", { name: "Export calls as CSV" });
        expect((csv as HTMLButtonElement).disabled).toBe(true);
    });

    test("sessions: live only exports the live sessions as JSON", async () => {
        mount(serving(richSnapshot), "/sessions?show=live");
        fireEvent.click(await screen.findByRole("button", { name: "Export sessions as JSON" }));
        const rows = JSON.parse((await blobs[0]?.text()) ?? "") as { status: string }[];
        expect(rows.length).toBeGreaterThan(0);
        expect(rows.every((r) => r.status === "live")).toBe(true);
    });

    test("overview: savings by plugin exports its measured rows without the share bar", async () => {
        mount(serving(richSnapshot), "/overview");
        fireEvent.click(
            await screen.findByRole("button", { name: "Export savings by plugin as CSV" }),
        );
        const [head, ...rest] = (await lines()) ?? [];
        expect(head).toBe("plugin,rows,saved");
        expect(rest.length).toBeGreaterThan(0);
    });
});
