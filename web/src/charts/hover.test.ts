// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, renderHook } from "@testing-library/react";
import { expect, test } from "vitest";
import { setHover, useHover } from "./hover";

test("charts in one group see the same hover; other groups do not", () => {
  const a = renderHook(() => useHover("g1"));
  const b = renderHook(() => useHover("g2"));
  const owner = Symbol("chart");
  act(() => setHover("g1", { index: 4, owner }));
  expect(a.result.current).toEqual({ index: 4, owner });
  expect(b.result.current).toBeNull();
  act(() => setHover("g1", null));
  expect(a.result.current).toBeNull();
});

test("no group, no hover", () => {
  expect(renderHook(() => useHover(undefined)).result.current).toBeNull();
});
