// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { expect, test } from "vitest";
import { PAGES } from "../pages";
import { hasIcon } from "./Icon";

// The sidebar looks an icon up by page id and silently renders an empty slot when the SVG is
// missing, so a new page would ship without one unless this fails (T407).
test("every page has a sidebar icon", () => {
  expect(PAGES.map((p) => p.id).filter((id) => !hasIcon(id))).toEqual([]);
});
