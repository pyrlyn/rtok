// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  connectionKey,
  createApi,
  DOCTOR_TIMEOUT_MS,
  pausedKey,
  snapshotKey,
  WRITE_TIMEOUT_MS,
  type Api,
} from "./query";
import { connectSample, isSampleRequested, sampleSnapshot } from "./sample";
import type { Cleared, Request, Snapshot } from "./snapshot.gen";
import type { Connect, Connection, Frame, Handlers } from "./ws";

// A scripted connection: the test plays the server by calling the captured handlers.
function scripted() {
  let handlers!: Handlers;
  const sent: unknown[] = [];
  let open = true;
  const connection: Connection = {
    send: (m) => (open ? (sent.push(m), true) : false),
    close: () => handlers.onState("closed"),
  };
  const connect: Connect = (h) => ((handlers = h), connection);
  // The server's answer to the i-th request sent: the frame under that request's `req`.
  const req = (i: number) => (sent[i] as Request).req as number;
  const answer = (i: number, frame: Frame) => handlers.onFrame({ ...frame, req: req(i) } as Frame);
  return {
    connect,
    sent,
    req,
    answer,
    server: () => handlers,
    setOpen: (v: boolean) => (open = v),
  };
}

let queryClient: QueryClient;
beforeEach(() => {
  queryClient = new QueryClient();
});
afterEach(() => vi.useRealTimers());

describe("cache wiring", () => {
  test("each snapshot replaces the cached one and connection state is tracked", () => {
    const s = scripted();
    createApi(queryClient, s.connect).open();
    s.server().onState("open");
    s.server().onFrame({ type: "snapshot", snapshot: sampleSnapshot });
    expect(queryClient.getQueryData(connectionKey)).toBe("open");
    expect(queryClient.getQueryData(snapshotKey)).toEqual(sampleSnapshot);

    const next: Snapshot = { ...sampleSnapshot, logs: ["later"] };
    s.server().onFrame({ type: "snapshot", snapshot: next });
    expect(queryClient.getQueryData<Snapshot>(snapshotKey)?.logs).toEqual(["later"]);
  });

  test("a failed tick keeps the last pages and sets the error; the next good frame clears it", () => {
    const s = scripted();
    createApi(queryClient, s.connect).open();
    s.server().onFrame({ type: "snapshot_error", error: "early" });
    expect(queryClient.getQueryData(snapshotKey)).toBeUndefined();

    s.server().onFrame({ type: "snapshot", snapshot: sampleSnapshot });
    s.server().onFrame({ type: "snapshot_error", error: "boom" });
    expect(queryClient.getQueryData<Snapshot>(snapshotKey)).toMatchObject({
      error: "boom",
      logs: sampleSnapshot.logs,
    });
    s.server().onFrame({ type: "snapshot", snapshot: sampleSnapshot });
    expect(queryClient.getQueryData<Snapshot>(snapshotKey)?.error).toBeUndefined();
  });

  test("open is idempotent and close shuts the connection", () => {
    const connect = vi.fn(scripted().connect);
    const api = createApi(queryClient, connect);
    api.open();
    api.open();
    expect(connect).toHaveBeenCalledTimes(1);
    api.close();
    expect(queryClient.getQueryData(connectionKey)).toBe("closed");
  });
});

describe("pause", () => {
  const rows = (n: number): Snapshot => ({
    ...sampleSnapshot,
    logs: Array.from({ length: n }, (_, i) => String(i)),
  });
  const shown = () => queryClient.getQueryData<Snapshot>(snapshotKey)?.logs;

  function paused() {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    s.server().onFrame({ type: "snapshot", snapshot: rows(1) });
    api.pause();
    return { s, api };
  }

  test("rows stay stable while frames arrive and the newest one lands on resume", () => {
    const { s, api } = paused();
    s.server().onFrame({ type: "snapshot", snapshot: rows(2) });
    s.server().onFrame({ type: "snapshot", snapshot: rows(3) });
    expect(queryClient.getQueryData(pausedKey)).toBe(true);
    expect(shown()).toEqual(["0"]);

    api.resume();
    expect(queryClient.getQueryData(pausedKey)).toBe(false);
    expect(shown()).toEqual(["0", "1", "2"]);
    s.server().onFrame({ type: "snapshot", snapshot: rows(4) });
    expect(shown()).toHaveLength(4);
  });

  test("the cache keeps its update time while paused, so the age is the shown snapshot's", () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000);
    const { s, api } = paused();
    const at = queryClient.getQueryState(snapshotKey)?.dataUpdatedAt;
    vi.setSystemTime(1_009_000);
    s.server().onFrame({ type: "snapshot", snapshot: rows(2) });
    expect(queryClient.getQueryState(snapshotKey)?.dataUpdatedAt).toBe(at);
    api.resume();
    expect(queryClient.getQueryState(snapshotKey)?.dataUpdatedAt).toBe(1_009_000);
  });

  test("a tick error that arrives while paused waits for resume, like any other frame", () => {
    const { s, api } = paused();
    s.server().onFrame({ type: "snapshot_error", error: "boom" });
    expect(queryClient.getQueryData<Snapshot>(snapshotKey)?.error).toBeUndefined();
    api.resume();
    expect(queryClient.getQueryData<Snapshot>(snapshotKey)).toMatchObject({ error: "boom" });
  });

  test("there is nothing to freeze before the first snapshot", () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    api.pause();
    expect(queryClient.getQueryData(pausedKey)).toBeUndefined();
    s.server().onFrame({ type: "snapshot", snapshot: rows(1) });
    expect(shown()).toEqual(["0"]);
  });

  test("the socket stays open and server replies still settle while paused", async () => {
    const { s, api } = paused();
    const text = api.expand("abc");
    s.server().onFrame({ type: "expand", id: "abc", text: "full" });
    await expect(text).resolves.toBe("full");
    expect(s.sent).toEqual([{ expand: "abc" }]);
  });
});

describe("set mutation", () => {
  test("sends the key and value; fails when not connected", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    await expect(api.set({ key: "plugins.a.enabled", value: true })).rejects.toThrow(
      "not connected",
    );
    api.open();
    const sent = api.set({ key: "plugins.a.enabled", value: true });
    s.server().onFrame({ type: "snapshot", snapshot: sampleSnapshot });
    await sent;
    expect(s.sent).toEqual([{ set: { key: "plugins.a.enabled", value: true } }]);
    s.setOpen(false);
    await expect(api.set({ key: "plugins.a.enabled", value: false })).rejects.toThrow(
      "not connected",
    );
  });

  test("a write waits for the server's answer: the next snapshot settles it, a message fails it", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    let settled = false;
    const written = api.project({ action: "select", project: "1" }).then(() => (settled = true));
    await Promise.resolve();
    expect(settled).toBe(false);
    s.server().onFrame({ type: "snapshot", snapshot: sampleSnapshot });
    await written;
    expect(settled).toBe(true);

    const refused = api.set({ key: "proxy.enabled", value: true });
    s.server().onFrame({ type: "message", text: "refused key proxy.enabled" });
    await expect(refused).rejects.toThrow("refused key proxy.enabled");
  });

  test("a write the server never answers fails instead of spinning for ever", async () => {
    vi.useFakeTimers();
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const silent = expect(api.set({ key: "plugins.a.enabled", value: true })).rejects.toThrow(
      "did not answer",
    );
    await vi.advanceTimersByTimeAsync(WRITE_TIMEOUT_MS);
    await silent;
  });
});

describe("expand mutation", () => {
  let s: ReturnType<typeof scripted>;
  let api: Api;
  beforeEach(() => {
    vi.useFakeTimers();
    s = scripted();
    api = createApi(queryClient, s.connect, 1000);
    api.open();
  });

  test("resolves on the frame with the matching id only", async () => {
    const p = api.expand("a1");
    expect(s.sent).toEqual([{ expand: "a1" }]);
    s.server().onFrame({ type: "expand", id: "other", text: "wrong" });
    s.server().onFrame({ type: "expand", id: "a1", text: "body" });
    await expect(p).resolves.toBe("body");
  });

  test("rejects on a server message", async () => {
    const p = api.expand("nope");
    s.server().onFrame({ type: "message", text: "unknown archive id: nope" });
    await expect(p).rejects.toThrow("unknown archive id: nope");
  });

  test("rejects on timeout, and a late reply is ignored", async () => {
    const p = api.expand("a1");
    const assertion = expect(p).rejects.toThrow("timed out");
    vi.advanceTimersByTime(1000);
    await assertion;
    expect(() => s.server().onFrame({ type: "expand", id: "a1", text: "late" })).not.toThrow();
  });

  test("rejects when the connection drops or was never open", async () => {
    const dropped = api.expand("a1");
    s.server().onState("closed");
    await expect(dropped).rejects.toThrow("connection closed");

    s.setOpen(false);
    await expect(api.expand("a2")).rejects.toThrow("not connected");
  });
});

describe("?sample source", () => {
  test("is selected by the query string", () => {
    expect(isSampleRequested("?sample")).toBe(true);
    expect(isSampleRequested("?x=1&sample=1")).toBe(true);
    expect(isSampleRequested("")).toBe(false);
  });

  test("serves the fixture offline, flips a plugin on set and answers expand", async () => {
    const api = createApi(queryClient, connectSample);
    api.open();
    await Promise.resolve();
    expect(queryClient.getQueryData(connectionKey)).toBe("open");
    expect(queryClient.getQueryData<Snapshot>(snapshotKey)?.plugins.map((p) => p.enabled)).toEqual([
      true,
      false,
      true,
      true,
      true,
      false,
    ]);

    const expanded = api.expand("abc");
    await api.set({ key: "plugins.read.enabled", value: true });
    await expect(expanded).resolves.toContain("abc");
    await Promise.resolve();
    expect(queryClient.getQueryData<Snapshot>(snapshotKey)?.plugins.map((p) => p.enabled)).toEqual([
      true,
      true,
      true,
      true,
      true,
      false,
    ]);
    expect(sampleSnapshot.plugins[1]?.enabled).toBe(false);
  });

  test("refuses a key outside the plugin allowlist", async () => {
    const api = createApi(queryClient, connectSample);
    api.open();
    // The fixture's first snapshot lands on a microtask and would settle the write too early.
    await Promise.resolve();
    await expect(api.set({ key: "proxy.enabled", value: true })).rejects.toThrow(
      "refused key proxy.enabled",
    );
  });
});

describe("doctor requests", () => {
  const selection = { keep: [], toggled: [{ source: "/p", path: "hooks.Stop[0]" }] };
  const other = { keep: [], toggled: [{ source: "/q", path: "hooks.Stop[1]" }] };
  const plan = { items: [], diff: "", refused: [], token: "t1" };
  const fixed = { text: "done", code: 0, replan: null };

  test("a plan and an apply each wait for the frame that echoes their req", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const planned = api.doctorPlan(selection);
    const applied = api.doctorApply(selection, "t1");
    expect(s.sent).toEqual([
      { doctor: { action: "plan", selection, confirmed: null }, req: expect.any(Number) },
      { doctor: { action: "apply", selection, confirmed: "t1" }, req: expect.any(Number) },
    ]);
    expect(s.req(0)).not.toBe(s.req(1));
    s.answer(1, { type: "doctorfixed", fixed });
    s.answer(0, { type: "doctorplan", plan });
    await expect(planned).resolves.toEqual(plan);
    await expect(applied).resolves.toEqual(fixed);
  });

  test("a late answer to a timed-out plan cannot resolve the next plan", async () => {
    vi.useFakeTimers();
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const lost = expect(api.doctorPlan(selection)).rejects.toThrow("doctorplan timed out");
    await vi.advanceTimersByTimeAsync(DOCTOR_TIMEOUT_MS);
    await lost;

    const retried = api.doctorPlan(other);
    const late = { ...plan, token: "from-a" };
    s.answer(0, { type: "doctorplan", plan: late });
    let settled = false;
    void retried.then(() => (settled = true));
    await Promise.resolve();
    expect(settled).toBe(false);

    s.answer(1, { type: "doctorplan", plan: { ...plan, token: "from-b" } });
    await expect(retried).resolves.toMatchObject({ token: "from-b" });
  });

  test("a frame of another kind, without a req or with an unknown one settles nothing", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const planned = api.doctorPlan(selection);
    s.answer(0, { type: "doctorfixed", fixed });
    s.server().onFrame({ type: "doctorplan", plan });
    s.server().onFrame({ type: "doctorplan", plan, req: 999 });
    let settled = false;
    void planned.then(() => (settled = true));
    await Promise.resolve();
    expect(settled).toBe(false);
    s.answer(0, { type: "doctorplan", plan });
    await expect(planned).resolves.toEqual(plan);
  });

  test("a refusal fails the request it names and no other one", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const refused = api.doctorPlan(selection);
    const waiting = api.doctorPlan(other);
    s.answer(0, { type: "message", text: "doctor needs an action and a selection" });
    await expect(refused).rejects.toThrow("doctor needs");
    s.answer(1, { type: "doctorplan", plan });
    await expect(waiting).resolves.toEqual(plan);
  });

  test("a refusal of an expired request is dropped; an untagged one fails writes, not requests", async () => {
    vi.useFakeTimers();
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const lost = expect(api.doctorPlan(selection)).rejects.toThrow("timed out");
    await vi.advanceTimersByTimeAsync(DOCTOR_TIMEOUT_MS);
    await lost;

    const planned = api.doctorPlan(other);
    const write = api.set({ key: "proxy.enabled", value: true });
    s.answer(0, { type: "message", text: "late refusal" });
    s.server().onFrame({ type: "message", text: "refused key proxy.enabled" });
    await expect(write).rejects.toThrow("refused key");
    s.answer(1, { type: "doctorplan", plan });
    await expect(planned).resolves.toEqual(plan);
  });

  test("a closed link and a missing link fail the request", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const dropped = api.doctorApply(selection, "t1");
    s.server().onState("closed");
    await expect(dropped).rejects.toThrow("connection closed");
    s.setOpen(false);
    await expect(api.doctorPlan(selection)).rejects.toThrow("not connected");
  });

  test("request ids continue across a reconnect, so the old socket's frame matches nothing", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const before = expect(api.doctorPlan(selection)).rejects.toThrow("connection closed");
    s.server().onState("closed");
    await before;
    api.reconnect();
    const after = api.doctorPlan(selection);
    expect(s.req(1)).toBeGreaterThan(s.req(0));
    s.answer(0, { type: "doctorplan", plan: { ...plan, token: "old socket" } });
    s.answer(1, { type: "doctorplan", plan });
    await expect(after).resolves.toEqual(plan);
  });

  test("the junk plan and its apply are told apart by req", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const cleared = { items: [], planned_bytes: 0, freed_bytes: 0 } as unknown as Cleared;
    const planned = api.junkPlan();
    const applied = api.junkApply(["/a"]);
    s.answer(1, { type: "junkcleared", cleared: { ...cleared, freed_bytes: 7 } });
    s.answer(0, { type: "junkplan", plan: cleared });
    await expect(planned).resolves.toBe(cleared);
    await expect(applied).resolves.toMatchObject({ freed_bytes: 7 });
  });

  test("the project plan is settled by req, whichever project the frame names", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const first = api.projectPlan("1");
    const second = api.projectPlan("1");
    s.answer(1, { type: "projectplan", project: "1", plan: "second" });
    s.answer(0, { type: "projectplan", project: "1", plan: "first" });
    await expect(first).resolves.toEqual({ project: "1", plan: "first" });
    await expect(second).resolves.toEqual({ project: "1", plan: "second" });
  });

  test("the sample machine plans with the terminal defaults and applies the selection", async () => {
    const api = createApi(queryClient, connectSample);
    api.open();
    const first = await api.doctorPlan({ keep: [], toggled: [] });
    expect(first.items.map((i) => [i.shared, i.selected])).toEqual([
      [false, true],
      [true, false],
      [false, true],
    ]);
    expect(first.diff).not.toContain("/work/app");
    const flipped = await api.doctorPlan({
      keep: [],
      toggled: [{ source: "/work/app/.claude/settings.json", path: "hooks.Stop[0].hooks[0]" }],
    });
    expect(flipped.diff).toContain("/work/app");
    const done = await api.doctorApply({ keep: [], toggled: [] }, first.token);
    expect(done).toMatchObject({ code: 0 });
    expect(done.text).toContain("2 entries removed");
  });

  test("the sample machine writes nothing for a confirmation of another preview", async () => {
    const api = createApi(queryClient, connectSample);
    api.open();
    const none = { keep: [], toggled: [] };
    const first = await api.doctorPlan(none);
    const flipped = {
      keep: [],
      toggled: [{ source: "/work/app/.claude/settings.json", path: "hooks.Stop[0].hooks[0]" }],
    };
    const refused = await api.doctorApply(flipped, first.token);
    expect(refused).toMatchObject({ code: 1 });
    expect(refused.replan?.diff).toContain("/work/app");
    expect(refused.text).not.toContain("removed");
    await expect(api.doctorApply(none, "")).resolves.toMatchObject({ code: 1 });
  });
});

describe("drill requests", () => {
  const request = { project: "1", expand: [], focus: null, depth: null, limit: null, query: "" };
  const frame = (project: number, more = 0) =>
    ({ type: "graph", graph: { project, nodes: [], edges: [], more } }) as unknown as Frame;

  test("each frame settles the request it echoes, whatever the order", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const one = api.drill(request);
    const two = api.drill({ ...request, project: "2" });
    expect(s.sent).toHaveLength(2);
    s.answer(1, frame(2));
    s.answer(0, frame(1));
    await expect(two).resolves.toMatchObject({ project: 2 });
    await expect(one).resolves.toMatchObject({ project: 1 });
  });

  test("two requests for one project are told apart, and the second may answer first", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const first = api.drill(request);
    const second = api.drill({ ...request, limit: 1000 });
    s.answer(1, frame(1, 0));
    s.answer(0, frame(1, 7));
    await expect(first).resolves.toMatchObject({ more: 7 });
    await expect(second).resolves.toMatchObject({ more: 0 });
  });

  test("a late frame of a timed-out drill cannot resolve the retry for the same project", async () => {
    vi.useFakeTimers();
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const lost = expect(api.drill(request)).rejects.toThrow("graph timed out");
    await vi.advanceTimersByTimeAsync(DOCTOR_TIMEOUT_MS);
    await lost;
    const retry = api.drill({ ...request, limit: 5 });
    s.answer(0, frame(1, 7));
    s.answer(1, frame(1, 2));
    await expect(retry).resolves.toMatchObject({ more: 2 });
  });

  test("a refusal fails the request, and so does a missing link", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const refused = api.drill(request);
    s.answer(0, { type: "message", text: "unknown project 1" });
    await expect(refused).rejects.toThrow("unknown project");
    s.setOpen(false);
    await expect(api.drill(request)).rejects.toThrow("not connected");
  });

  test("a request nobody answers times out and leaves the queue", async () => {
    vi.useFakeTimers();
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const lost = expect(api.drill(request)).rejects.toThrow("graph timed out");
    await vi.advanceTimersByTimeAsync(DOCTOR_TIMEOUT_MS);
    await lost;
  });
});

describe("diff requests", () => {
  const request = { project: "1", from: [], to: null, export: null };
  const frame = (project: string, from: string) =>
    ({ type: "diff", project, diff: { from, to: "working", projects: [] } }) as unknown as Frame;

  test("each frame settles the request it echoes", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const one = api.diff(request);
    const two = api.diff({ ...request, project: "2" });
    expect(s.sent[0]).toEqual({ diff: request, req: expect.any(Number) });
    s.answer(1, frame("2", "v2"));
    s.answer(0, frame("1", "v1"));
    await expect(two).resolves.toMatchObject({ from: "v2" });
    await expect(one).resolves.toMatchObject({ from: "v1" });
  });

  test("two diffs of one project against different refs are told apart", async () => {
    vi.useFakeTimers();
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const lost = expect(api.diff({ ...request, from: ["v1"] })).rejects.toThrow("diff timed out");
    await vi.advanceTimersByTimeAsync(DOCTOR_TIMEOUT_MS);
    await lost;
    const retry = api.diff({ ...request, from: ["v2"] });
    s.answer(0, frame("1", "v1"));
    s.answer(1, frame("1", "v2"));
    await expect(retry).resolves.toMatchObject({ from: "v2" });
  });

  test("a refusal fails the request, and a drill frame of the same project does not settle it", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const refused = api.diff(request);
    s.answer(0, {
      type: "graph",
      graph: { project: 1, nodes: [], edges: [] },
    } as unknown as Frame);
    s.answer(0, { type: "message", text: "1 is not a git repository" });
    await expect(refused).rejects.toThrow("not a git repository");
  });
});

describe("export and import requests", () => {
  const exportRequest = {
    project: "1",
    format: "json",
    level: "overview",
    depth: null,
    focus: null,
    scale: null,
    transparent: false,
  } as const;
  const file = (name: string) =>
    ({ type: "export", file: { name, mime: "application/json", data: "e30=" } }) as Frame;
  const imported = (name: string) =>
    ({
      type: "imported",
      name,
      export: { projects: [], nodes: [], edges: [] },
    }) as unknown as Frame;

  test("concurrent exports and an import each get their own answer", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const json = api.exportGraph(exportRequest);
    const svg = api.exportGraph({ ...exportRequest, format: "svg" });
    const opened = api.importGraph({ name: "a.json", text: "{}" });
    s.answer(2, imported("a.json"));
    s.answer(1, file("graph.svg"));
    s.answer(0, file("graph.json"));
    await expect(json).resolves.toMatchObject({ name: "graph.json" });
    await expect(svg).resolves.toMatchObject({ name: "graph.svg" });
    await expect(opened).resolves.toMatchObject({ name: "a.json" });
  });

  test("a late export cannot resolve the retry, and an import refusal names its import", async () => {
    vi.useFakeTimers();
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const lost = expect(api.exportGraph(exportRequest)).rejects.toThrow("export timed out");
    await vi.advanceTimersByTimeAsync(DOCTOR_TIMEOUT_MS);
    await lost;
    const retry = api.exportGraph({ ...exportRequest, format: "svg" });
    const bad = api.importGraph({ name: "b.json", text: "nope" });
    s.answer(0, file("graph.json"));
    s.answer(2, { type: "message", text: "b.json is not a graph export" });
    s.answer(1, file("graph.svg"));
    await expect(bad).rejects.toThrow("not a graph export");
    await expect(retry).resolves.toMatchObject({ name: "graph.svg" });
  });
});

describe("call stream", () => {
  const batch = (now: number) =>
    ({
      type: "calls",
      calls: { now, running: [], feed: [], heat_window_s: 300, windows: [] },
    }) as Frame;

  test("the first listener subscribes, the last one unsubscribes, a second shares the stream", () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const seen: number[] = [];
    const stopA = api.calls((b) => seen.push(b.now));
    const stopB = api.calls((b) => seen.push(b.now * 10));
    expect(s.sent).toEqual([{ calls: { subscribe: true } }]);
    s.server().onFrame(batch(3));
    expect(seen).toEqual([3, 30]);
    stopA();
    expect(s.sent).toHaveLength(1);
    stopB();
    expect(s.sent).toEqual([{ calls: { subscribe: true } }, { calls: { subscribe: false } }]);
  });

  test("a new socket subscribes again while a listener is still registered", () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    api.calls(() => {});
    s.server().onState("closed");
    s.server().onState("open");
    expect(s.sent).toEqual([{ calls: { subscribe: true } }, { calls: { subscribe: true } }]);
  });
});
