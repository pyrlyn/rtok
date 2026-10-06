// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test } from "vitest";

// T414: the brand pack is the only source of tokens, fonts, icons and logos. A copy in `web/`
// drifts the moment the brand changes, so any file byte-identical to one in `brand/` (or the
// `@pyrlyn/brand` base it installs) fails here.
const web = fileURLToPath(new URL("..", import.meta.url));
const brand = fileURLToPath(new URL("../../brand", import.meta.url));
const BUILD_OUTPUT = new Set(["node_modules", "dist", "storybook-static", "test-results"]);

function files(dir: string, skip: (name: string) => boolean): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    if (skip(e.name)) return [];
    const path = join(dir, e.name);
    return e.isDirectory() ? files(path, skip) : e.isFile() ? [path] : [];
  });
}

const digest = (path: string) => createHash("sha256").update(readFileSync(path)).digest("hex");

test("web/ keeps no copy of a brand file", () => {
  const bySize = new Map<number, string[]>();
  for (const p of files(brand, () => false)) {
    const n = statSync(p).size;
    bySize.set(n, [...(bySize.get(n) ?? []), p]);
  }
  expect(bySize.size).toBeGreaterThan(0);
  // A copy is as long as its original, so a stat rules out almost every file before any read:
  // hashing all of them overran the 5 s timeout on a loaded host (T427).
  const copies = files(web, (name) => BUILD_OUTPUT.has(name)).flatMap((p) => {
    const twins = bySize.get(statSync(p).size) ?? [];
    if (twins.length === 0) return [];
    const d = digest(p);
    return twins
      .filter((b) => digest(b) === d)
      .map((b) => `${relative(web, p)} = ${relative(brand, b)}`);
  });
  expect(copies).toEqual([]);
});
