// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { createContext, useContext, useSyncExternalStore } from "react";

/** The hovered x index of one sync group, and which chart owns it (only that one shows a tooltip). */
export interface Hover {
  index: number;
  owner: symbol;
  /** The stacked segment under the pointer, when the owner can tell (the calls legend lights it). */
  series?: string | null;
}

const groups = new Map<string, Hover | null>();
const listeners = new Set<() => void>();

export function setHover(group: string, hover: Hover | null) {
  if (groups.get(group) === hover) return;
  groups.set(group, hover);
  for (const l of listeners) l();
}

const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

export function useHover(group: string | undefined): Hover | null {
  return useSyncExternalStore(subscribe, () => (group ? (groups.get(group) ?? null) : null));
}

/**
 * Marks the charts inside one card as a single owner. A card that already shows the tooltip of its
 * own chart keeps its live readout still, so the same value is never printed twice (T414.16).
 */
export const HoverScope = createContext<symbol | null>(null);
export const useScope = () => useContext(HoverScope);
