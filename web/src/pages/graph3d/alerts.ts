// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { Alert, Kind, ProjectRow } from "../../api/snapshot.gen";
import { hm } from "../format";

/** The wording of `rtok doctor` (health.rs), so the page and the terminal name a problem alike. */
export const WORDS: Record<Kind, string> = {
  missing: "missing",
  unreachable: "unreachable",
  backend_down: "backend down",
  link_broken: "link broken",
};

export interface AlertGroup {
  kind: Kind;
  alerts: Alert[];
  /** The earliest check among the group: the problem began no later than this. */
  since: number;
  names: string;
}

export const alertsOf = (rows: ProjectRow[]): Alert[] => rows.flatMap((r) => r.alerts ?? []);

/** One group per kind, so a whole disk that went away reads as one line, not twenty. */
export function groupAlerts(alerts: Alert[]): AlertGroup[] {
  const by = new Map<Kind, Alert[]>();
  for (const a of alerts) by.set(a.kind, [...(by.get(a.kind) ?? []), a]);
  return (Object.keys(WORDS) as Kind[]).flatMap((kind) => {
    const group = by.get(kind);
    return group
      ? [
          {
            kind,
            alerts: group,
            since: Math.min(...group.map((a) => a.since)),
            names: group.map((a) => a.project).join(", "),
          },
        ]
      : [];
  });
}

/** "b, c missing since 14:02 (2 projects)", or the cause for a single project. */
export function describeGroup(g: AlertGroup): string {
  const [only] = g.alerts;
  const tail = g.alerts.length === 1 ? only!.detail : `${g.alerts.length} projects`;
  return `${g.names} ${WORDS[g.kind]} since ${hm(g.since)} (${tail})`;
}

/** An alert is the same alert while its project and kind are; a later check only moves `since`. */
const keyOf = (a: Alert) => `${a.root}\0${a.kind}`;

/** What a new snapshot changed: alerts it raised and alerts that are gone. */
export function diffAlerts(prev: Alert[], next: Alert[]) {
  const before = new Set(prev.map(keyOf));
  const after = new Set(next.map(keyOf));
  return {
    raised: next.filter((a) => !before.has(keyOf(a))),
    recovered: prev.filter((a) => !after.has(keyOf(a))),
  };
}

export interface ToastMessage {
  id: number;
  tone: "fail" | "ok";
  text: string;
}

/** One toast per kind and direction, so several projects changing at once do not stack up. */
export function toastsFor(diff: ReturnType<typeof diffAlerts>): Omit<ToastMessage, "id">[] {
  return [
    ...groupAlerts(diff.raised).map((g) => ({
      tone: "fail" as const,
      text: describeGroup(g),
    })),
    ...groupAlerts(diff.recovered).map((g) => ({
      tone: "ok" as const,
      text: `${g.names} ${WORDS[g.kind]}: recovered`,
    })),
  ];
}

/** Ids of the registry projects that have an alert, for the badges on the nodes and edges. */
export const alertedIds = (rows: ProjectRow[]): Set<number> =>
  new Set(rows.filter((r) => (r.alerts?.length ?? 0) > 0).map((r) => r.id));
