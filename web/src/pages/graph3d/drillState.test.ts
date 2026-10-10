// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import {
  breadcrumb,
  DEPTH_DEFAULT,
  drillSearch,
  focusOn,
  openProject,
  openSymbol,
  parseDrill,
  toggleFile,
} from "./drillState";

describe("drill URL state", () => {
  test("no project means the overview", () => {
    expect(parseDrill({})).toBeNull();
    expect(parseDrill({ p: "" })).toBeNull();
    expect(parseDrill({ x: ["a.rs"] })).toBeNull();
  });

  test("a project id, even one the router parsed into a number", () => {
    expect(parseDrill({ p: 7 })).toEqual({
      project: "7",
      expand: [],
      focus: null,
      depth: DEPTH_DEFAULT,
    });
  });

  test.each([["/etc/passwd"], ["1; drop"], ["-1"], ["12345678901"], [{ a: 1 }]])(
    "%j is not a registry id",
    (p) => expect(parseDrill({ p })).toBeNull(),
  );

  test("files, a focus and a depth survive a round trip through the search params", () => {
    const state = {
      project: "2",
      expand: ["src/a.rs", "src/b.rs"],
      focus: { path: "src/a.rs", name: "run" },
      depth: 3,
    };
    expect(parseDrill(drillSearch(state))).toEqual(state);
  });

  test("defaults are left out of the URL", () => {
    const search = drillSearch(openProject(4));
    expect(search).toEqual({
      p: "4",
      x: undefined,
      fp: undefined,
      fn: undefined,
      d: undefined,
    });
    expect(drillSearch(null)).toEqual({
      p: undefined,
      x: undefined,
      fp: undefined,
      fn: undefined,
      d: undefined,
    });
  });

  test("junk in the lists and depth falls back instead of throwing", () => {
    const many = Array.from({ length: 80 }, (_, i) => `f${i}`);
    const s = parseDrill({ p: "1", x: [...many, 5, null, {}, "f1"], fp: "a", d: 9 })!;
    expect(s.expand).toHaveLength(50);
    expect(s.expand.slice(0, 2)).toEqual(["f0", "f1"]);
    expect(s.focus).toBeNull();
    expect(s.depth).toBe(DEPTH_DEFAULT);
    expect(parseDrill({ p: "1", x: "one.rs", d: "2.5" })).toMatchObject({
      expand: ["one.rs"],
      depth: 2,
    });
    expect(parseDrill({ p: "1", x: ["a".repeat(900)] })!.expand[0]).toHaveLength(500);
  });

  test("expanding toggles a file and ends a focus; a focus keeps the files", () => {
    const base = openProject(1);
    const open = toggleFile(base, "a.rs");
    expect(open.expand).toEqual(["a.rs"]);
    expect(toggleFile(open, "a.rs").expand).toEqual([]);
    const focused = focusOn(open, { path: "a.rs", name: "f" });
    expect(focused.expand).toEqual(["a.rs"]);
    expect(toggleFile(focused, "b.rs").focus).toBeNull();
  });

  test("a symbol of another project opens that project with nothing else", () => {
    expect(openSymbol(5, { path: "lib.rs", name: "open" })).toEqual({
      project: "5",
      expand: [],
      focus: { path: "lib.rs", name: "open" },
      depth: DEPTH_DEFAULT,
    });
  });
});

describe("breadcrumb", () => {
  test("the project is the place when nothing is expanded or focused", () => {
    expect(breadcrumb(openProject(1), "rtok")).toEqual([
      { label: "All projects", to: null },
      { label: "rtok" },
    ]);
  });

  test("every step but the last leads back up", () => {
    const state = toggleFile(openProject(1), "src/plugins/graph");
    const crumbs = breadcrumb(state, "rtok");
    expect(crumbs.map((c) => c.label)).toEqual(["All projects", "rtok", "src/plugins/graph"]);
    expect(crumbs[1]!.to).toEqual(openProject(1));
    expect(crumbs[2]!.to).toBeUndefined();
  });

  test("a focus names its file", () => {
    const state = focusOn(openProject(1), { path: "src/a.rs", name: "run" });
    expect(breadcrumb(state, "rtok").at(-1)).toEqual({ label: "src/a.rs" });
  });
});
