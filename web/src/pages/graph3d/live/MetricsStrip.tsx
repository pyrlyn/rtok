// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ReactNode } from "react";
import { focusRing } from "../../../ui/cx";
import { tones } from "../../../ui/Kpi";

function Stat({ label, children, tone }: { label: string; children: ReactNode; tone?: string }) {
    return (
        <span className="flex shrink-0 items-baseline gap-1 whitespace-nowrap">
            <span className="text-fg-subtle">{label}</span>
            <b className={`font-semibold ${tone ?? ""}`}>{children}</b>
        </span>
    );
}

/**
 * The live numbers of a narrow screen in one line (T329.52): what is running, how many calls and
 * how many tokens they saved, over the window the chips chose. The line is the button that opens
 * and closes the full cards below it, so a phone keeps the picture in view until asked for more.
 */
export function MetricsStrip({
    running,
    calls,
    saved,
    window,
    frozen,
    open,
    onToggle,
    controls,
}: {
    running: ReactNode;
    calls: ReactNode;
    saved: ReactNode;
    /** The window the figures cover. */
    window: string;
    frozen: boolean;
    open: boolean;
    onToggle(): void;
    /** The id of the region the line opens. */
    controls: string;
}) {
    return (
        <button
            type="button"
            aria-expanded={open}
            aria-controls={controls}
            onClick={onToggle}
            className={`${focusRing} flex min-h-touch w-full cursor-pointer items-center gap-3 rounded-md border border-border bg-bg/60 px-2.5 text-xs text-fg hover:border-border-strong`}
        >
            <Stat label="running">{running}</Stat>
            <Stat label="calls">{calls}</Stat>
            <Stat label="saved" tone={tones.saved}>
                {saved}
            </Stat>
            <span className="ml-auto flex min-w-0 items-center gap-1.5 text-2xs text-fg-subtle">
                {frozen && <span className="text-warn-fg">frozen</span>}
                {/* The window is what gives way when a 320 px screen runs out of room. */}
                <span className="truncate">{window}</span>
                <svg
                    aria-hidden="true"
                    viewBox="0 0 12 12"
                    className={`size-3 shrink-0 fill-none stroke-current motion-safe:transition-transform ${open ? "rotate-180" : ""}`}
                    strokeWidth={1.5}
                >
                    <path d="M2 4.5 6 8.5 10 4.5" />
                </svg>
            </span>
        </button>
    );
}
