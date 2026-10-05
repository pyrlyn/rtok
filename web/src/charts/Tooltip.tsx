// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { flip, offset, shift, useFloating } from "@floating-ui/react-dom";
import { useLayoutEffect, type ReactNode } from "react";
import { createPortal } from "react-dom";
import type { Anchor } from "./renderer";

/** One tooltip for every chart and mark, hung from a viewport point. */
export function Tooltip({
    id,
    anchor,
    children,
}: {
    id: string;
    anchor: Anchor;
    children: ReactNode;
}) {
    const { refs, floatingStyles } = useFloating({
        placement: "top",
        strategy: "fixed",
        middleware: [offset(10), flip(), shift({ padding: 8 })],
    });
    useLayoutEffect(() => {
        refs.setReference({
            getBoundingClientRect: () => new DOMRect(anchor.x, anchor.y, 0, 0),
        });
    }, [refs, anchor.x, anchor.y]);
    return createPortal(
        <div
            ref={refs.setFloating}
            id={id}
            role="tooltip"
            style={floatingStyles}
            className="pointer-events-none z-50 min-w-32 rounded-md border border-border-strong bg-surface px-2.5 py-2 text-2xs text-fg-muted shadow-lg"
        >
            {children}
        </div>,
        document.body,
    );
}
