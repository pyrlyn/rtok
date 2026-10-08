// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import type { Column } from "./DataTable";
import { exportTable, toCsv, toJson, type ExportTable } from "./tableExport";

const table = (rows: ExportTable["rows"]): ExportTable => ({
  ids: ["a", "b"],
  headers: ["a", "b"],
  rows,
});

describe("toCsv", () => {
  test("writes CRLF lines and quotes only what needs it", () => {
    expect(toCsv(table([["plain", 1.5]]))).toBe("a,b\r\nplain,1.5\r\n");
    expect(
      toCsv(
        table([
          ["x,y", 'say "hi"'],
          ["two\nlines", "cr\rhere"],
        ]),
      ),
    ).toBe('a,b\r\n"x,y","say ""hi"""\r\n"two\nlines","cr\rhere"\r\n');
  });

  test("null, undefined and booleans", () => {
    expect(
      toCsv(
        table([
          [null, undefined],
          [true, false],
        ]),
      ),
    ).toBe("a,b\r\n,\r\ntrue,false\r\n");
  });

  test("an empty table is its header row; no columns is nothing", () => {
    expect(toCsv(table([]))).toBe("a,b\r\n");
    expect(toCsv({ ids: [], headers: [], rows: [] })).toBe("");
  });

  test.each(["=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx", "\nx", "＝1", "＋1", "－1", "＠x"])(
    "text starting like a formula is quoted behind an apostrophe: %j",
    (cell) => {
      const csv = toCsv(table([[cell, "ok"]]));
      expect(csv).toBe(`a,b\r\n"'${cell}",ok\r\n`);
    },
  );

  test("a quote inside a guarded cell is still doubled", () => {
    expect(toCsv(table([['=HYPERLINK("http://x")', 1]]))).toBe(
      `a,b\r\n"'=HYPERLINK(""http://x"")",1\r\n`,
    );
  });

  test("a negative number is data, not a formula", () => {
    expect(toCsv(table([[-5, "a-b"]]))).toBe("a,b\r\n-5,a-b\r\n");
  });

  test("a header that looks like a formula is not trusted either", () => {
    const csv = toCsv({ ids: ["x"], headers: ["=x"], rows: [] });
    expect(csv).toBe('"\'=x"\r\n');
  });
});

describe("toJson", () => {
  test("objects keyed by column id, missing values null", () => {
    expect(
      JSON.parse(
        toJson(
          table([
            ["x", undefined],
            [null, 2],
          ]),
        ),
      ),
    ).toEqual([
      { a: "x", b: null },
      { a: null, b: 2 },
    ]);
  });

  test("an empty table is an empty array", () => {
    expect(toJson(table([]))).toBe("[]");
  });
});

describe("exportTable", () => {
  interface Row {
    n: string;
    v: number;
  }
  const columns: Column<Row>[] = [
    { id: "n", header: "name", sortValue: (r) => r.n, cell: (r) => r.n },
    { id: "v", header: "value", exportValue: (r) => r.v * 10, cell: (r) => r.v },
    // No plain value: a visual-only column is left out of the file.
    { id: "bar", header: "bar", cell: () => null },
  ];
  const rows: Row[] = [
    { n: "b", v: 1 },
    { n: "a", v: 2 },
  ];

  test("keeps the table's order, takes exportValue over sortValue and drops value-less columns", () => {
    expect(exportTable(columns, rows, { id: "n", desc: false })).toEqual({
      ids: ["n", "v"],
      headers: ["name", "value"],
      rows: [
        ["a", 20],
        ["b", 10],
      ],
    });
    expect(exportTable(columns, rows).rows).toEqual([
      ["b", 10],
      ["a", 20],
    ]);
  });
});
