// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Component, Level, Score } from "../../api/snapshot.gen";
import type { PillTone } from "../../ui/Pill";

/** The ring colour per level, as brand roles so both themes (and the 3D stage, via `resolveRole`) get their own value. */
export const HEALTH_ROLE: Record<Level, string> = {
  good: "var(--pyr-success-fg)",
  warn: "var(--pyr-warn-fg)",
  bad: "var(--pyr-danger-fg)",
  missing: "var(--pyr-danger-fg)",
  // Grey: there is no score yet, so the ring must not read as good or bad.
  indexing: "var(--pyr-fg-subtle)",
};

export const HEALTH_TONE: Record<Level, PillTone> = {
  good: "ok",
  warn: "warn",
  bad: "fail",
  missing: "fail",
  indexing: "muted",
};

const COMPONENTS: Component[] = ["freshness", "backend", "links"];

/**
 * The level of a bare score, for `ProjectRow.scope_health`, which carries no level of its own.
 * The cut-offs are the server's (`WARN_BELOW` and `NOTICE_BELOW` in `health/score.rs`); a project's
 * own level always comes from the server.
 */
export function levelOf(score: number): Level {
  return score >= 80 ? "good" : score >= 50 ? "warn" : "bad";
}

/** What the ring says in words: the score, or why there is none. */
export function healthLabel(s: Score): string {
  if (s.level === "indexing") return "indexing";
  if (s.level === "missing") return "missing";
  return String(s.score ?? 0);
}

const pct = (n: number) => `${Math.round(n * 100)}%`;

/** The three components on one line, for the list and the breakdown. */
export function componentsLine(s: Score): string {
  return COMPONENTS.map((c) => `${c} ${pct(s.components[c])}`).join(" · ");
}

/** The whole breakdown as plain lines: a native tooltip and the canvas tooltips have no markup. */
export function healthLines(s: Score): string[] {
  return [
    `health ${healthLabel(s)}`,
    componentsLine(s),
    ...(s.reasons ?? []).map((r) => `${r.text}; fix: ${r.fix}`),
  ];
}
