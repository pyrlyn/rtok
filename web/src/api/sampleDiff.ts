// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// A stand-in for the server's `diff` answer (T329.35), for `?sample`, stories and tests: one change of
// each colour in the sample `rtok` project, laid over the files `sampleDrill` draws.
import type { DiffDef, DiffReport, DiffRequest, ProjectRow } from "./snapshot.gen";
import type { Frame } from "./ws";

const def = (path: string, name: string, line: number, over: Partial<DiffDef> = {}): DiffDef => ({
  name,
  kind: "function",
  path,
  line,
  ...over,
});

const DRILL = "src/plugins/graph/drill.rs";
const EXPORT_NOTE =
  "note: against an export only added and removed symbols are listed; changes and edges need a revision\n";

/** The report for `req`, or throws what the server's refusal says. */
export function sampleDiff(req: DiffRequest, rows: ProjectRow[]): DiffReport {
  const row = rows.find((r) => String(r.id) === req.project || r.root === req.project);
  if (!row) throw new Error(`unknown project ${req.project}`);
  const name = req.export?.name;
  if (req.export && !req.export.text.includes('"schema"')) {
    throw new Error(`${name} is not a graph export`);
  }
  const empty = (project: string) => ({
    project,
    from: "HEAD",
    changed: [],
    added: [],
    removed: [],
    renamed: [],
    moved: [],
    edges_added: [],
    edges_removed: [],
    not_analysed: [],
  });
  const sample = row.name === "rtok";
  // An export keeps no signatures and no edges, so it can only say what appeared and vanished.
  const git = !req.export;
  return {
    from: name ? `export ${name}` : (req.from[0] ?? "HEAD"),
    to: req.to ?? "working",
    projects: [
      sample
        ? {
            ...empty(row.name),
            changed: git
              ? [
                  def(DRILL, "run", 40, {
                    signature_changed: true,
                    callers: ["main src/main.rs d1", "run_hook src/hook.rs d1"],
                  }),
                  def(DRILL, "load", 60, {
                    signature_changed: false,
                    callers: ["run src/plugins/graph/drill.rs d1"],
                  }),
                ]
              : [],
            added: [def(DRILL, "resolve", 80)],
            removed: [def("src/hook.rs", "legacy_hook", 12, { callers: ["main src/main.rs d1"] })],
            moved: git
              ? [
                  {
                    from: def("src/db.rs", "open_store", 5),
                    to: def("src/store.rs", "open_store", 5),
                  },
                ]
              : [],
            edges_added: git ? [{ path: DRILL, scope: "run", name: "resolve" }] : [],
            edges_removed: git
              ? [{ path: "src/hook.rs", scope: "run_hook", name: "legacy_hook" }]
              : [],
            not_analysed: git ? [{ path: "docs/guide.md", reason: "no grammar" }] : [],
          }
        : empty(row.name),
    ],
    links_added: sample && !git ? [{ from: "rtok", to: "ketch", kind: "manual" }] : [],
    links_removed: [],
    ...(git ? {} : { notes: EXPORT_NOTE }),
  };
}

/** The server's reply to a diff request: the frame, or the refusal message it sends. */
export function diffReply(req: DiffRequest, rows: ProjectRow[]): Frame {
  try {
    return { type: "diff", project: req.project, diff: sampleDiff(req, rows) };
  } catch (e) {
    return { type: "message", text: e instanceof Error ? e.message : String(e) };
  }
}
