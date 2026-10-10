// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ProjectRow } from "../../../api/snapshot.gen";
import type { DrillState } from "../drillState";
import { scopeOf } from "../scene";

/**
 * The names of the projects the live part's scope holds (T329 §8b): the project part 1 drilled
 * into, else the selected one, with everything its links reach. `null` when there is no scope, so
 * no call is ever "outside" a scope that was never chosen.
 */
export function scopeNames(
  rows: ProjectRow[],
  drill: DrillState | null,
): ReadonlySet<string> | null {
  const from = drill ? Number(drill.project) : rows.find((p) => p.selected)?.id;
  const ids = scopeOf(rows, from);
  return ids.size ? new Set(rows.filter((p) => ids.has(p.id)).map((p) => p.name)) : null;
}

/** A call names its project; one the scope lacks is counted and listed but never lights the canvas. */
export const outsideScope = (scope: ReadonlySet<string> | null, project: string | null) =>
  scope !== null && project !== null && !scope.has(project);
