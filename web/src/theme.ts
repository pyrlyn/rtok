// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useCallback, useEffect, useState } from "react";
import { readStored, writeStored } from "./storage";

const KEY = "rtok-theme";
const LIGHT_QUERY = "(prefers-color-scheme: light)";

// Same rule as the pre-paint script in index.html, which cannot import this module.
export function resolveDark(): boolean {
  const saved = readStored(KEY);
  return saved ? saved === "dark" : !matchMedia(LIGHT_QUERY).matches;
}

function apply(dark: boolean) {
  const root = document.documentElement;
  root.classList.toggle("dark", dark);
  root.classList.toggle("light", !dark);
  root.setAttribute("data-theme", dark ? "dark" : "light");
  // The browser chrome follows the canvas role; read it after the attribute above so the theme is already applied.
  const bg = getComputedStyle(root).getPropertyValue("--pyr-bg").trim();
  if (bg) document.querySelector('meta[name="theme-color"]')?.setAttribute("content", bg);
}

export function useTheme() {
  const [dark, setDark] = useState(resolveDark);

  useEffect(() => apply(dark), [dark]);

  useEffect(() => {
    const query = matchMedia(LIGHT_QUERY);
    // An explicit choice wins over the OS; only an unset theme tracks it.
    const onChange = () => {
      if (!readStored(KEY)) setDark(resolveDark());
    };
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  const toggle = useCallback(() => {
    const next = !dark;
    writeStored(KEY, next ? "dark" : "light");
    setDark(next);
  }, [dark]);

  return { dark, toggle };
}
