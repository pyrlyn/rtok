// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { createHash } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
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
  const brandFiles = new Map(files(brand, () => false).map((p) => [digest(p), p]));
  expect(brandFiles.size).toBeGreaterThan(0);
  const copies = files(web, (name) => BUILD_OUTPUT.has(name))
    .filter((p) => brandFiles.has(digest(p)))
    .map((p) => `${relative(web, p)} = ${relative(brand, brandFiles.get(digest(p))!)}`);
  expect(copies).toEqual([]);
});
