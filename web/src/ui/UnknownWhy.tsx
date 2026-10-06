// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useState } from "react";
import { Button, Tooltip, TooltipTrigger } from "react-aria-components";
import { focusRing, tooltipBox } from "./cx";

/** The "?" behind an Unknown value and its reason, on hover, focus or tap (T414.17). */
export function UnknownWhy({ why, label }: { why: string; label: string }) {
    const [open, setOpen] = useState(false);
    return (
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
    );
}
