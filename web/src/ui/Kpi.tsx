// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

const tones = { default: "", ok: "text-success-fg", warn: "text-warn-fg", fail: "text-delta-fg" };

export function Kpi({
    label,
    value,
    sub,
    viz,
    to,
    tone = "default",
}: {
    label: string;
    value: ReactNode;
    sub?: ReactNode;
    viz?: ReactNode;
    /** Makes the whole card a link; the chart stays out of it so the card holds no nested control. */
    to?: { to: `/${string}`; search?: Record<string, string | undefined> };
    tone?: keyof typeof tones;
}) {
    return (
        <div className="glass relative flex min-w-0 flex-col gap-1.5 p-3">
            <span className="truncate text-2xs font-semibold tracking-kicker text-fg-subtle uppercase">
                {to ? (
                    <Link
                        {...to}
                        // The overlay is the click target; the ring goes on it so a keyboard user sees the card, not the label.
                        className="outline-none after:absolute after:inset-0 after:rounded-[var(--pyr-radius-lg)] focus-visible:after:shadow-ring"
                    >
                        {label}
                    </Link>
                ) : (
                    label
                )}
            </span>
            <div className="min-w-0">
                <div
                    className={`text-xl leading-none font-semibold whitespace-nowrap ${tones[tone]}`}
                >
                    {value}
                </div>
                {sub && <div className="mt-1.5 truncate text-2xs text-fg-muted">{sub}</div>}
            </div>
            {viz && (
                <div className="relative z-10 mt-auto flex h-7 items-end pt-1 [&>svg]:h-7 [&>svg]:w-full">
                    {viz}
                </div>
            )}
        </div>
    );
}
