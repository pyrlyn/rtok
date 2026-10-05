// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useState, type ReactNode } from "react";
import { Button, Tooltip, TooltipTrigger } from "react-aria-components";
import { focusRing, tooltipBox } from "./cx";

/**
 * A value the page has no data for, with the reason one hover, focus or tap away (T414.17).
 * `label` names an absence that is itself a fact ("none", "top-level"), so it does not read
 * as missing data.
 */
export function Unknown({ why, label = "Unknown" }: { why: string; label?: string }) {
    const [open, setOpen] = useState(false);
    return (
        <span className="inline-flex items-center gap-1 whitespace-nowrap text-fg-subtle">
            {label}
            <TooltipTrigger isOpen={open} onOpenChange={setOpen} delay={250}>
                <Button
                    aria-label={label === "Unknown" ? "Why is this unknown?" : `Why ${label}?`}
                    // Touch has no hover, so a tap toggles the reason there.
                    onPress={(e) => {
                        if (e.pointerType === "touch") setOpen((o) => !o);
                    }}
                    className={`${focusRing} grid size-3.5 place-items-center rounded-full border border-current font-sans text-[9px] leading-none font-semibold hover:text-fg-muted`}
                >
                    ?
                </Button>
                <Tooltip offset={6} className={`${tooltipBox} max-w-64`}>
                    {why}
                </Tooltip>
            </TooltipTrigger>
        </span>
    );
}

/** The value, or Unknown with why it is missing. CLI text pages write "-" for none. */
export const orUnknown = (value: ReactNode, why: string, label?: string) =>
    value == null || value === "" || value === "-" ? <Unknown why={why} label={label} /> : value;
