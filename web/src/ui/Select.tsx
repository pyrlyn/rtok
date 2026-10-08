// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ReactNode } from "react";
import { focusRing } from "./cx";

// A native `<select>`: the platform picker is the right control on a phone and is already
// keyboard and screen-reader complete, so only the field look of the Pyrlyn input spec is ours.
export function Select({
    value,
    onChange,
    label,
    children,
}: {
    value: string;
    onChange: (next: string) => void;
    label: string;
    children: ReactNode;
}) {
    return (
        <select
            aria-label={label}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            className={`${focusRing} h-control rounded-md border border-border bg-bg/60 px-2 text-xs text-fg transition-colors duration-fast ease-standard max-md:h-touch max-md:text-sm hover:border-border-strong disabled:cursor-not-allowed disabled:opacity-40`}
        >
            {children}
        </select>
    );
}
