// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Score } from "../../api/snapshot.gen";
import { Pill } from "../../ui/Pill";
import {
    componentsLine,
    HEALTH_ROLE,
    HEALTH_TONE,
    healthLabel,
    healthLines,
    levelOf,
} from "./health";

const R = 9;
const C = 2 * Math.PI * R;

/** A project's health as a ring: the arc is the score, the colour its level, the number inside says it without colour. */
export function HealthRing({ health, size = 24 }: { health: Score; size?: number }) {
    const label = healthLabel(health);
    const indexing = health.level === "indexing";
    const arc = indexing ? "3 3" : `${((health.score ?? 0) / 100) * C} ${C}`;
    return (
        // The hover text sits on a wrapper: a `<title>` would join the name of the button the ring is in.
        <span title={healthLines(health).join("\n")} className="inline-flex shrink-0">
            <svg
                role="img"
                aria-label={`health ${label}`}
                data-level={health.level}
                viewBox="0 0 24 24"
                width={size}
                height={size}
                className="text-fg"
            >
                <circle
                    cx={12}
                    cy={12}
                    r={R}
                    fill="none"
                    stroke="currentColor"
                    opacity={0.2}
                    strokeWidth={3}
                />
                <circle
                    cx={12}
                    cy={12}
                    r={R}
                    fill="none"
                    strokeWidth={3}
                    style={{ stroke: HEALTH_ROLE[health.level] }}
                    strokeDasharray={arc}
                    transform="rotate(-90 12 12)"
                />
                <text
                    x={12}
                    y={12}
                    textAnchor="middle"
                    dominantBaseline="central"
                    fontSize={health.score === 100 ? 7 : 8}
                    fill="currentColor"
                    className="font-mono"
                >
                    {indexing ? "…" : health.level === "missing" ? "0" : health.score}
                </text>
            </svg>
        </span>
    );
}

/** The three components, then what lowered the score and what to do about it. */
export function HealthBreakdown({ health }: { health: Score }) {
    const reasons = health.reasons ?? [];
    return (
        <div aria-label="health breakdown" className="flex flex-col gap-0.5 text-2xs text-fg-muted">
            <p>{componentsLine(health)}</p>
            {reasons.length > 0 && (
                <ul className="flex flex-col gap-0.5">
                    {reasons.map((r) => (
                        <li key={`${r.component}:${r.text}`}>
                            {r.text} <span className="text-fg-subtle">fix: {r.fix}</span>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}

/** The lowest score in the selected project's scope, beside the project; nothing while every member is on its first index. */
export function ScopeHealth({ score }: { score: number | null | undefined }) {
    if (score == null) return null;
    return (
        <span title="the lowest health score in this project's scope">
            <Pill tone={HEALTH_TONE[levelOf(score)]} dot>
                scope {score}
            </Pill>
        </span>
    );
}
