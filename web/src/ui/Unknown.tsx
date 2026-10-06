// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { Suspense, type ReactNode } from "react";
import { lazyPart } from "./lazyPart";

// The tooltip brings React Aria; the word itself paints first and stays if the chunk fails.
const UnknownWhy = lazyPart("the Unknown tooltip", () =>
    import("./UnknownWhy").then((m) => m.UnknownWhy),
);

/**
 * A value the page has no data for, with the reason one hover, focus or tap away (T414.17).
 * `label` names an absence that is itself a fact ("none", "top-level"), so it does not read
 * as missing data.
 */
export function Unknown({ why, label = "Unknown" }: { why: string; label?: string }) {
    return (
        <span className="inline-flex items-center gap-1 whitespace-nowrap text-fg-subtle">
            {label}
            <Suspense>
                <UnknownWhy why={why} label={label} />
            </Suspense>
        </span>
    );
}

/** The value, or Unknown with why it is missing. CLI text pages write "-" for none. */
export const orUnknown = (value: ReactNode, why: string, label?: string) =>
    value == null || value === "" || value === "-" ? <Unknown why={why} label={label} /> : value;
