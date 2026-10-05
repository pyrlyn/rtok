// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ReactNode } from "react";
import { isMac } from "./shortcuts";

// Kept apart from Palette.tsx: the header shows these on every page, the palette loads lazily.
export const Kbd = ({ children }: { children: ReactNode }) => (
    <kbd className="rounded border border-border px-1 font-mono text-2xs text-fg-subtle">
        {children}
    </kbd>
);

export const paletteKeys = () => (isMac() ? "⌘ K" : "Ctrl K");
