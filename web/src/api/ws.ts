// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Typed client for the `/ws` contract (T310.2). Transport-agnostic on purpose: the socket
// constructor and timers are injectable so Vitest drives it without a server or a browser.
import type {
  CallsView,
  Cleared,
  DiffReport,
  DrillGraph,
  Export,
  ExportFile,
  Fixed,
  Plan,
  Request,
  ServerFrame,
  Snapshot,
} from "./snapshot.gen";

export type ConnectionState = "connecting" | "open" | "closed";

// The server's panic fallback is `{"type":"snapshot","error":...}` with none of the page
// fields, so a snapshot frame is split in two rather than cast blindly to `Snapshot`.
// `req` is the id of the request a frame answers (T623): the server echoes it on every reply,
// a refusal included, and a push such as a snapshot carries none.
export type Frame = (
  | { type: "snapshot"; snapshot: Snapshot }
  | { type: "snapshot_error"; error: string }
  | ServerFrame
) & { req?: number };

/** `frame` as the server sends it for a request that carried `req`; a request without one gets it bare. */
export const echoReq = (frame: Frame, req?: number | null): Frame =>
  typeof req === "number" ? { ...frame, req } : frame;

export interface Handlers {
  onState(state: ConnectionState): void;
  onFrame(frame: Frame): void;
}

export interface Connection {
  /** `false` when the frame was not sent because the link is not open. */
  send(message: Request): boolean;
  close(): void;
}

export type Connect = (handlers: Handlers) => Connection;

export interface Socket {
  onopen: (() => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: (() => void) | null;
  send(data: string): void;
  close(): void;
}

export interface WsOptions {
  socket?: new (url: string) => Socket;
  location?: Pick<Location, "protocol" | "host">;
  baseDelayMs?: number;
  maxDelayMs?: number;
  /** The `/ws` access token (S2); read again on every (re)connect. Defaults to {@link pageToken}. */
  token?: () => string | null;
}

const TOKEN_KEY = "rtok.web.token";

/**
 * The token `rtok web` wrote to `<RTOK_HOME>/web-token`. A browser cannot set headers on a
 * WebSocket, so it is passed as `?token=` on the page URL once, kept in `sessionStorage` for the
 * tab, and removed from the address bar so it does not linger in history or screenshots.
 */
export function pageToken(): string | null {
  try {
    const url = new URL(globalThis.location.href);
    const fromUrl = url.searchParams.get("token");
    if (fromUrl) {
      globalThis.sessionStorage.setItem(TOKEN_KEY, fromUrl);
      url.searchParams.delete("token");
      globalThis.history.replaceState(null, "", url);
    }
    return globalThis.sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function wsUrl(
  location: Pick<Location, "protocol" | "host">,
  token?: string | null,
): string {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  const query = token ? `?token=${encodeURIComponent(token)}` : "";
  return `${scheme}//${location.host}/ws${query}`;
}

export function parseFrame(raw: unknown): Frame | null {
  if (typeof raw !== "string") return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const v = value as Record<string, unknown>;
  const frame = parseBody(v);
  return frame && typeof v.req === "number" ? { ...frame, req: v.req } : frame;
}

function parseBody(v: Record<string, unknown>): Frame | null {
  switch (v.type) {
    case "message":
      return typeof v.text === "string" ? { type: "message", text: v.text } : null;
    case "expand":
      return typeof v.id === "string" && typeof v.text === "string"
        ? { type: "expand", id: v.id, text: v.text }
        : null;
    case "doctorplan": {
      const plan = v.plan as Record<string, unknown> | null;
      return plan && Array.isArray(plan.items) && typeof plan.diff === "string"
        ? { type: "doctorplan", plan: plan as unknown as Plan }
        : null;
    }
    case "doctorfixed": {
      const fixed = v.fixed as Record<string, unknown> | null;
      return fixed && typeof fixed.text === "string" && typeof fixed.code === "number"
        ? { type: "doctorfixed", fixed: fixed as unknown as Fixed }
        : null;
    }
    case "projectplan":
      return typeof v.project === "string" && typeof v.plan === "string"
        ? { type: "projectplan", project: v.project, plan: v.plan }
        : null;
    case "junkplan": {
      const plan = v.plan as Record<string, unknown> | null;
      return plan && Array.isArray(plan.items) && typeof plan.planned_bytes === "number"
        ? { type: "junkplan", plan: plan as unknown as Cleared }
        : null;
    }
    case "junkcleared": {
      const cleared = v.cleared as Record<string, unknown> | null;
      return cleared && Array.isArray(cleared.items) && typeof cleared.freed_bytes === "number"
        ? { type: "junkcleared", cleared: cleared as unknown as Cleared }
        : null;
    }
    case "calls": {
      const calls = v.calls as Record<string, unknown> | null;
      return calls && Array.isArray(calls.windows) && typeof calls.now === "number"
        ? { type: "calls", calls: calls as unknown as CallsView }
        : null;
    }
    case "graph": {
      const g = v.graph as Record<string, unknown> | null;
      return g && Array.isArray(g.nodes) && Array.isArray(g.edges) && typeof g.project === "number"
        ? { type: "graph", graph: g as unknown as DrillGraph }
        : null;
    }
    case "diff": {
      const d = v.diff as Record<string, unknown> | null;
      return d && Array.isArray(d.projects) && typeof v.project === "string"
        ? { type: "diff", project: v.project, diff: d as unknown as DiffReport }
        : null;
    }
    case "export": {
      const f = v.file as Record<string, unknown> | null;
      return f &&
        typeof f.name === "string" &&
        typeof f.mime === "string" &&
        typeof f.data === "string"
        ? { type: "export", file: f as unknown as ExportFile }
        : null;
    }
    case "imported": {
      const e = v.export as Record<string, unknown> | null;
      return typeof v.name === "string" && e && Array.isArray(e.projects) && Array.isArray(e.nodes)
        ? { type: "imported", name: v.name, export: e as unknown as Export }
        : null;
    }
    case "snapshot":
      if (Array.isArray(v.plugins)) return { type: "snapshot", snapshot: v as unknown as Snapshot };
      return typeof v.error === "string" ? { type: "snapshot_error", error: v.error } : null;
    default:
      return null;
  }
}

export function connectWs(
  handlers: Handlers,
  {
    socket: SocketCtor = globalThis.WebSocket as unknown as new (url: string) => Socket,
    location: loc = globalThis.location,
    baseDelayMs = 500,
    maxDelayMs = 10_000,
    token = pageToken,
  }: WsOptions = {},
): Connection {
  let socket: Socket | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let attempt = 0;
  let stopped = false;

  const scheduleRetry = () => {
    handlers.onState("closed");
    if (stopped) return;
    const delay = Math.min(baseDelayMs * 2 ** attempt, maxDelayMs);
    attempt += 1;
    timer = setTimeout(open, delay);
  };

  function open() {
    handlers.onState("connecting");
    let current: Socket;
    try {
      current = new SocketCtor(wsUrl(loc, token()));
    } catch {
      // A constructor that throws (blocked scheme, bad host) is a failed attempt, not a crash.
      scheduleRetry();
      return;
    }
    socket = current;
    current.onopen = () => {
      attempt = 0;
      handlers.onState("open");
    };
    current.onmessage = (event) => {
      const frame = parseFrame(event.data);
      if (frame) handlers.onFrame(frame);
    };
    current.onclose = () => {
      if (socket === current) {
        socket = null;
        scheduleRetry();
      }
    };
  }

  open();

  return {
    send(message) {
      if (!socket) return false;
      try {
        socket.send(JSON.stringify(message));
        return true;
      } catch {
        // CONNECTING sockets throw on send; the caller reports the failed send.
        return false;
      }
    },
    close() {
      stopped = true;
      clearTimeout(timer);
      const current = socket;
      socket = null;
      current?.close();
      handlers.onState("closed");
    },
  };
}
