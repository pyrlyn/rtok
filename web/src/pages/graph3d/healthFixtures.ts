// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { project } from "../../api/sampleRows";
import type { ProjectRow } from "../../api/snapshot.gen";

/** One project per ring: 100, 60 with two reasons, first index running, and a directory that is gone. */
export const healthRows = (): ProjectRow[] => [
  project(1, "steady", {
    selected: true,
    scope_health: 60,
    links: [{ to: 2, kind: "manual", name: "lagging", reason: null }],
  }),
  project(2, "lagging", {
    state: "stale",
    health: {
      components: { backend: 0.6, freshness: 0.5, links: 0.73 },
      level: "warn",
      score: 60,
      reasons: [
        {
          component: "freshness",
          text: "3 files pending (30%)",
          fix: "run `rtok graph index /work/lagging`",
        },
        {
          component: "backend",
          text: "rust-analyzer is not installed",
          fix: "install the server; it is picked up within one health-check interval, or restart",
        },
      ],
    },
  }),
  project(3, "fresh", {
    state: "indexing",
    index: null,
    health: {
      components: { backend: 1, freshness: 0, links: 1 },
      level: "indexing",
      score: null,
      reasons: [
        { component: "freshness", text: "the first index is running", fix: "wait for it to end" },
      ],
    },
  }),
  project(4, "gone", {
    state: "missing",
    missing: true,
    index: null,
    health: {
      components: { backend: 0, freshness: 0, links: 0 },
      level: "missing",
      score: 0,
      reasons: [
        {
          component: "freshness",
          text: "the directory no longer exists",
          fix: "run `rtok graph projects remove gone`",
        },
      ],
    },
  }),
];
