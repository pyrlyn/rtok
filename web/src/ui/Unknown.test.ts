// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { isValidElement, type ReactElement } from "react";
import { expect, test } from "vitest";
import { orUnknown, Unknown } from "./Unknown";

const unknown = (v: unknown) =>
  isValidElement(v) && v.type === Unknown
    ? (v as ReactElement<{ why: string; label?: string }>).props
    : null;

test("missing values become Unknown with their reason", () => {
  for (const v of [null, undefined, "", "-"]) {
    expect(unknown(orUnknown(v, "why"))).toEqual({ why: "why", label: undefined });
  }
  expect(unknown(orUnknown(null, "why", "none"))).toEqual({ why: "why", label: "none" });
});

test("real values pass through, zero included", () => {
  expect(orUnknown(0, "why")).toBe(0);
  expect(orUnknown("opus", "why")).toBe("opus");
});
