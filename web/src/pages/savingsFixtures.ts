// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Δtok trend data (T414.13) for `?sample`, stories and tests. Every number is made up; the shape
// is `Overview.savings`, and each day's `saved` is the sum of its plugins, as the Rust model sums.
import type { SavingsDay } from "../api/snapshot.gen";

const day = (d: string, rows: number, plugins: Record<string, number>): SavingsDay => {
  const values = Object.values(plugins);
  return {
    day: d,
    rows,
    saved: values.length ? values.reduce((s, v) => s + v, 0) : null,
    plugins,
  };
};

// Three days without rows (a gap, never a zero) and five plugins, so two share the `other` bar.
export const savingsDays: SavingsDay[] = [
  day("2026-09-25", 12, { shell: 4_200, read: 1_800 }),
  day("2026-09-26", 30, { shell: 9_100, read: 3_300, archive: 2_000 }),
  day("2026-09-27", 0, {}),
  day("2026-09-28", 18, { shell: 5_000, graph: 900 }),
  day("2026-09-29", 41, { shell: 12_400, read: 6_100, archive: 3_900, graph: 700 }),
  day("2026-09-30", 22, { shell: 7_300, read: 2_600, ledger: 150 }),
  day("2026-10-01", 0, {}),
  day("2026-10-02", 0, {}),
  day("2026-10-03", 9, { read: 2_200 }),
  day("2026-10-04", 27, { shell: 8_800, archive: -400 }),
  day("2026-10-05", 35, { shell: 10_500, read: 4_400, archive: 2_600 }),
  day("2026-10-06", 48, { shell: 14_200, read: 5_900, archive: 3_100, graph: 1_200 }),
  day("2026-10-07", 31, { shell: 9_700, read: 3_800, archive: 1_500 }),
  day("2026-10-08", 14, { shell: 4_100, read: 1_600 }),
];

/** The same fourteen days with no Measurement row anywhere: the panel shows its empty state. */
export const noSavingsDays: SavingsDay[] = savingsDays.map((d) => day(d.day, 0, {}));
