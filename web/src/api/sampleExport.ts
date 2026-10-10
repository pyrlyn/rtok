// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// A stand-in for the server's `export` and `import` answers (T329.40), for `?sample`, stories and
// tests. The real files come from `rtok graph export`; these are small but have its shape.
import type {
  ClientMessage,
  Export,
  ExportRequest,
  ImportRequest,
  ProjectRow,
  Proj,
  Node,
} from "./snapshot.gen";
import type { Frame } from "./ws";

const SCHEMA = "rtok.graph.v1";
const TYPES = {
  json: ["json", "application/json"],
  svg: ["svg", "image/svg+xml"],
  png: ["png", "image/png"],
} as const;

const proj = (r: ProjectRow): Proj => ({
  id: r.id,
  name: r.name,
  root: `~/work/${r.name}`,
  origin: r.origin,
  backend: "tags",
  health: "ok",
  indexed_at: null,
});

/** The export for `req`, redacted like the server's: the home directory is `~`. */
export function sampleExport(req: ExportRequest, rows: ProjectRow[]): Export {
  const row = rows.find((r) => String(r.id) === req.project || r.root === req.project);
  if (!row) throw new Error(`unknown project ${req.project}`);
  const scope = [row, ...row.links.flatMap((l) => rows.filter((r) => r.id === l.to))];
  const level = req.focus ? "focus" : req.level;
  const nodes: Node[] =
    level === "overview"
      ? []
      : [
          {
            id: `${row.id}:src/lib.rs:1:${req.focus ?? "main"}`,
            project: row.id,
            kind: "function",
            name: req.focus ?? "main",
            path: "src/lib.rs",
            line: 1,
          },
        ];
  return {
    schema: SCHEMA,
    projects: scope.map(proj),
    links: row.links.map((l) => ({
      from: row.id,
      to: l.to,
      kind: l.kind,
      reason: l.reason,
      references: 0,
    })),
    nodes,
    edges: [],
    meta: {
      scope: scope.map((r) => r.name),
      level,
      focus: req.focus,
      depth: req.focus ? (req.depth ?? 2) : null,
      exported_at: 0,
      rtok_version: "sample",
      redacted: true,
      partial: false,
      notes: [],
    },
  };
}

export function exportReply(req: ExportRequest, rows: ProjectRow[]): Frame {
  try {
    const doc = sampleExport(req, rows);
    const [ext, mime] = TYPES[req.format];
    const body = req.format === "json" ? `${JSON.stringify(doc, null, 2)}\n` : `<svg>${ext}</svg>`;
    return {
      type: "export",
      file: {
        name: `rtok-graph-${doc.projects[0]?.name}-${doc.meta.level}.${ext}`,
        mime,
        data: btoa(body),
      },
    };
  } catch (e) {
    return { type: "message", text: e instanceof Error ? e.message : String(e) };
  }
}

/** What `export::parse` answers: the file, or the refusal the server's message carries. */
export function importReply(req: ImportRequest): Frame {
  try {
    const doc = JSON.parse(req.text) as Export;
    if (doc.schema !== SCHEMA) {
      return {
        type: "message",
        text: `${req.name}: schema ${JSON.stringify(doc.schema)}, this rtok reads ${SCHEMA}`,
      };
    }
    return { type: "imported", name: req.name, export: doc };
  } catch {
    return { type: "message", text: `${req.name} is not a graph export` };
  }
}

/** The answer to an export or import message, or `null` for any other message. */
export function exportAnswer(m: ClientMessage, rows: ProjectRow[]): Frame | null {
  if ("export" in m) return exportReply(m.export, rows);
  if ("import" in m) return importReply(m.import);
  return null;
}
