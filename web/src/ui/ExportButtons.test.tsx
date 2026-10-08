// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { Column } from "./DataTable";
import { ExportButtons } from "./ExportButtons";

interface Row {
    n: string;
}
const columns: Column<Row>[] = [
    { id: "n", header: "name", sortValue: (r) => r.n, cell: (r) => r.n },
];

let blobs: Blob[];
let saved: string[];

beforeEach(() => {
    blobs = [];
    saved = [];
    URL.createObjectURL = (b: Blob | MediaSource) => {
        blobs.push(b as Blob);
        return "blob:test";
    };
    URL.revokeObjectURL = () => {};
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
        this: HTMLAnchorElement,
    ) {
        saved.push(this.download);
    });
});
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("ExportButtons", () => {
    test("names both buttons and saves the rows in the table's order", async () => {
        render(
            <ExportButtons
                label="savings by plugin"
                rows={[{ n: "b" }, { n: "a" }]}
                columns={columns}
                sort={{ id: "n", desc: false }}
            />,
        );
        expect(screen.getByRole("group", { name: "Export savings by plugin" })).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "Export savings by plugin as CSV" }));
        fireEvent.click(screen.getByRole("button", { name: "Export savings by plugin as JSON" }));
        expect(saved).toEqual(["rtok-savings-by-plugin.csv", "rtok-savings-by-plugin.json"]);
        expect(blobs.map((b) => b.type)).toEqual(["text/csv;charset=utf-8", "application/json"]);
        expect(await blobs[0]?.text()).toBe("name\r\na\r\nb\r\n");
        expect(JSON.parse((await blobs[1]?.text()) ?? "")).toEqual([{ n: "a" }, { n: "b" }]);
    });

    test("is disabled when there is nothing to export", () => {
        render(<ExportButtons label="calls" rows={[]} columns={columns} />);
        const csv = screen.getByRole("button", {
            name: "Export calls as CSV",
        }) as HTMLButtonElement;
        fireEvent.click(csv);
        expect(csv.disabled).toBe(true);
        expect(saved).toEqual([]);
    });
});
