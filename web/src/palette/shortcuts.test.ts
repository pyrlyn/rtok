// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom

import { describe, expect, test } from "vitest";
import { PAGES } from "../pages";
import { GO, isPaletteKey, isTyping, pageForKey } from "./shortcuts";

describe("g <key> map", () => {
  test("every page has one key and no two pages share it", () => {
    const keys = PAGES.map((p) => GO[p.id]);
    expect(keys.every((k) => /^[a-z]$/.test(k))).toBe(true);
    expect(new Set(keys).size).toBe(PAGES.length);
  });

  test("a key resolves back to its page", () => {
    for (const p of PAGES) expect(pageForKey(GO[p.id])).toBe(p.id);
    expect(pageForKey("z")).toBeUndefined();
  });
});

test("fields swallow single-letter shortcuts, other elements do not", () => {
  const el = (html: string) => {
    document.body.innerHTML = html;
    return document.body.firstElementChild;
  };
  expect(isTyping(el("<input>"))).toBe(true);
  expect(isTyping(el("<textarea></textarea>"))).toBe(true);
  expect(isTyping(el("<select></select>"))).toBe(true);
  expect(isTyping(el('<div contenteditable="true"></div>'))).toBe(true);
  expect(isTyping(el("<button></button>"))).toBe(false);
  expect(isTyping(null)).toBe(false);
});

test("the palette key is ⌘K or Ctrl+K, not plain k or Alt", () => {
  const k = (mods: Partial<KeyboardEvent>) => ({
    key: "k",
    metaKey: false,
    ctrlKey: false,
    altKey: false,
    ...mods,
  });
  expect(isPaletteKey(k({ metaKey: true }))).toBe(true);
  expect(isPaletteKey(k({ ctrlKey: true, key: "K" }))).toBe(true);
  expect(isPaletteKey(k({}))).toBe(false);
  expect(isPaletteKey(k({ ctrlKey: true, altKey: true }))).toBe(false);
});
