// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// State of the "Clear safe junk" button (T330.7). The server owns the plan; the page only
// walks idle -> planning -> ready -> confirming -> applying -> done, and sends the delete
// message from `confirming` alone.
import type { Cleared, Planned } from "../api/snapshot.gen";

export type JunkPhase =
  | "idle"
  | "planning"
  | "ready"
  | "confirming"
  | "applying"
  | "done"
  | "error";

export interface JunkState {
  phase: JunkPhase;
  plan: Cleared | null;
  result: Cleared | null;
  error: string | null;
}

export type JunkAction =
  | { type: "plan" }
  | { type: "planned"; plan: Cleared }
  | { type: "ask" }
  | { type: "cancel" }
  | { type: "applying" }
  | { type: "applied"; result: Cleared }
  | { type: "failed"; error: string }
  | { type: "reset" };

export const initialJunk: JunkState = { phase: "idle", plan: null, result: null, error: null };

/** What the delete would take: planned items that carry the `clear` action. */
export const removable = (plan: Cleared | null): Planned[] =>
  plan ? plan.items.filter((i) => i.planned && i.action === "clear") : [];

export function junkReducer(state: JunkState, action: JunkAction): JunkState {
  switch (action.type) {
    case "plan":
      return state.phase === "idle" || state.phase === "done" || state.phase === "error"
        ? { ...initialJunk, phase: "planning" }
        : state;
    case "planned":
      return state.phase === "planning"
        ? { ...state, plan: action.plan, phase: "ready", error: null }
        : state;
    case "ask":
      return state.phase === "ready" && removable(state.plan).length > 0
        ? { ...state, phase: "confirming" }
        : state;
    case "cancel":
      return state.phase === "confirming" || state.phase === "ready"
        ? { ...state, phase: "idle", plan: null }
        : state;
    case "applying":
      return state.phase === "confirming" ? { ...state, phase: "applying" } : state;
    case "applied":
      return state.phase === "applying"
        ? { ...state, phase: "done", result: action.result, plan: null, error: null }
        : state;
    case "failed":
      // Back to the first button: a plan that failed to apply is stale, so it is asked again.
      return { ...initialJunk, phase: "error", error: action.error };
    case "reset":
      return initialJunk;
  }
}

export interface KindGroup {
  agent: string;
  kind: string;
  items: Planned[];
  bytes: number;
}

/** The plan by agent and kind, in the order the server listed them. */
export function groupPlan(items: Planned[]): KindGroup[] {
  const groups = new Map<string, KindGroup>();
  for (const i of items) {
    const key = `${i.agent}\u0000${i.kind}`;
    const g = groups.get(key) ?? { agent: i.agent, kind: i.kind, items: [], bytes: 0 };
    g.items.push(i);
    if (i.planned) g.bytes += i.bytes;
    groups.set(key, g);
  }
  return [...groups.values()];
}
