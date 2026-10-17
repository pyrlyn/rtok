// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { project } from "../../../api/sampleRows";
import { outsideScope, scopeNames } from "./scope";

const link = { to: 2, kind: "manual", name: "ketch", reason: null } as const;
const rows = [
  project(1, "rtok", { selected: true, links: [link] }),
  project(2, "ketch"),
  project(3, "pyrlyn"),
];
const drill = (id: string) => ({ project: id, expand: [], focus: null, depth: 1 });

describe("the scope of the live part", () => {
  test("the selected project and what its links reach", () => {
    expect([...scopeNames(rows, null)!].sort()).toEqual(["ketch", "rtok"]);
  });

  test("the drilled project wins over the selected one", () => {
    expect([...scopeNames(rows, drill("3"))!]).toEqual(["pyrlyn"]);
  });

  test("no selection, or a project the registry lacks, is no scope", () => {
    expect(
      scopeNames(
        rows.map((r) => ({ ...r, selected: false })),
        null,
      ),
    ).toBeNull();
    expect(scopeNames(rows, drill("99"))).toBeNull();
  });

  test("a call is outside only when it names a project the scope lacks", () => {
    const scope = scopeNames(rows, null);
    expect(outsideScope(scope, "pyrlyn")).toBe(true);
    expect(outsideScope(scope, "ketch")).toBe(false);
    expect(outsideScope(scope, null)).toBe(false);
    expect(outsideScope(null, "pyrlyn")).toBe(false);
  });
});
