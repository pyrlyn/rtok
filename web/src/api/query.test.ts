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
import type { Snapshot } from "./snapshot.gen";
import {
  answering,
  type Connect,
  type Connection,
  type Frame,
  type Handlers,
  type Outgoing,
} from "./ws";

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
  /** The server's answer to the `index`-th message sent: it echoes that request's id. */
  const answer = (index: number, frame: Frame) =>
    handlers.onFrame(answering(frame, (sent[index] as Outgoing).rid));
  return { connect, sent, answer, server: () => handlers, setOpen: (v: boolean) => (open = v) };
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
    s.answer(0, { type: "expand", id: "abc", text: "full" });
    await expect(text).resolves.toBe("full");
    expect(s.sent).toMatchObject([{ expand: "abc" }]);
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
    expect(s.sent).toMatchObject([{ expand: "a1" }]);
    s.answer(0, { type: "expand", id: "other", text: "wrong" });
    s.answer(0, { type: "expand", id: "a1", text: "body" });
    await expect(p).resolves.toBe("body");
  });

  test("rejects on a server message", async () => {
    const p = api.expand("nope");
    s.answer(0, { type: "message", text: "unknown archive id: nope" });
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
  const plan = { items: [], diff: "", refused: [] };

  test("a plan and an apply each wait for their own frame, in the order asked", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const planned = api.doctorPlan(selection);
    const applied = api.doctorApply(selection);
    expect(s.sent).toMatchObject([
      { doctor: { action: "plan", selection } },
      { doctor: { action: "apply", selection } },
    ]);
    s.answer(1, { type: "doctorfixed", fixed: { text: "done", code: 0 } });
    s.answer(0, { type: "doctorplan", plan });
    await expect(planned).resolves.toEqual(plan);
    await expect(applied).resolves.toEqual({ text: "done", code: 0 });
  });

  test("a refusal, a closed link and a missing link fail the request", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const refused = api.doctorPlan(selection);
    s.answer(0, { type: "message", text: "doctor needs an action and a selection" });
    await expect(refused).rejects.toThrow("doctor needs");
    const dropped = api.doctorApply(selection);
    s.server().onState("closed");
    await expect(dropped).rejects.toThrow("connection closed");
    s.setOpen(false);
    await expect(api.doctorPlan(selection)).rejects.toThrow("not connected");
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
    const done = await api.doctorApply({ keep: [], toggled: [] });
    expect(done).toMatchObject({ code: 0 });
    expect(done.text).toContain("2 entries removed");
  });
});

describe("drill requests", () => {
  const request = { project: "1", expand: [], focus: null, depth: null, limit: null, query: "" };
  const frame = (project: number, more = 0) =>
    ({ type: "graph", graph: { project, nodes: [], edges: [], more } }) as unknown as Frame;

  test("an answer settles the request it echoes, whatever the order", async () => {
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

  test("two requests for one project are told apart by id, not by the order asked", async () => {
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

  test("an answer settles the request it echoes", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const one = api.diff(request);
    const two = api.diff({ ...request, project: "2" });
    expect(s.sent[0]).toMatchObject({ diff: request });
    s.answer(1, frame("2", "v2"));
    s.answer(0, frame("1", "v1"));
    await expect(two).resolves.toMatchObject({ from: "v2" });
    await expect(one).resolves.toMatchObject({ from: "v1" });
  });

  test("a refusal fails the request, and a drill answer to another request does not settle it", async () => {
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    const refused = api.diff(request);
    const drilled = api.drill({
      project: "1",
      expand: [],
      focus: null,
      depth: null,
      limit: null,
      query: "",
    });
    s.answer(1, { type: "graph", graph: { project: 1, nodes: [], edges: [] } } as unknown as Frame);
    s.answer(0, { type: "message", text: "1 is not a git repository" });
    await expect(refused).rejects.toThrow("not a git repository");
    await expect(drilled).resolves.toMatchObject({ project: 1 });
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

// T623: an answer settles the request whose id it echoes, never the next one of its kind. The
// first request outlives the client's timer, so its answer arrives while the retry waits.
describe("request correlation", () => {
  const ridOf = (message: unknown) => (message as { rid?: string }).rid;
  const selA = { keep: [], toggled: [{ source: "/p", path: "hooks.Stop[0]" }] };
  const selB = { keep: [], toggled: [{ source: "/p", path: "hooks.Stop[1]" }] };
  const planFor = (diff: string) => ({ items: [], diff, refused: [] });
  const request = { project: "1", expand: [], focus: null, depth: null, limit: null, query: "" };
  const exportRequest = {
    project: "1",
    level: "overview",
    format: "json",
    depth: null,
    focus: null,
    scale: null,
    transparent: false,
  } as const;

  function connected() {
    vi.useFakeTimers();
    const s = scripted();
    const api = createApi(queryClient, s.connect);
    api.open();
    /** The server's answer to the `index`-th message sent, carrying that message's id. */
    const answer = (index: number, frame: object) =>
      s.server().onFrame({ ...frame, rid: ridOf(s.sent[index]) } as unknown as Frame);
    /** True once the promise has settled either way. */
    const watch = <T>(promise: Promise<T>) => {
      const seen = { value: undefined as T | undefined, error: undefined as unknown, done: false };
      promise.then(
        (value) => Object.assign(seen, { value, done: true }),
        (error) => Object.assign(seen, { error, done: true }),
      );
      return seen;
    };
    return { s, api, answer, watch };
  }

  /** A times out unseen; the caller then sends B and delivers A's late answer. */
  const expire = async (a: Promise<unknown>, ms = DOCTOR_TIMEOUT_MS) => {
    const lost = expect(a).rejects.toThrow("timed out");
    await vi.advanceTimersByTimeAsync(ms);
    await lost;
  };

  test("each message carries its own request id", () => {
    const { s, api } = connected();
    void api.doctorPlan(selA).catch(() => {});
    void api.doctorPlan(selB).catch(() => {});
    const [a, b] = s.sent.map(ridOf);
    expect(typeof a).toBe("string");
    expect(a).not.toBe(b);
  });

  test("control: timely answers settle their own requests", async () => {
    const { api, answer } = connected();
    const a = api.doctorPlan(selA);
    answer(0, { type: "doctorplan", plan: planFor("A") });
    await expect(a).resolves.toMatchObject({ diff: "A" });
    const b = api.doctorPlan(selB);
    answer(1, { type: "doctorplan", plan: planFor("B") });
    await expect(b).resolves.toMatchObject({ diff: "B" });
  });

  test("two requests in flight settle by id, whatever order the answers come in", async () => {
    const { api, answer } = connected();
    const a = api.doctorPlan(selA);
    const b = api.doctorPlan(selB);
    answer(1, { type: "doctorplan", plan: planFor("B") });
    answer(0, { type: "doctorplan", plan: planFor("A") });
    await expect(a).resolves.toMatchObject({ diff: "A" });
    await expect(b).resolves.toMatchObject({ diff: "B" });
  });

  test("a doctor plan's late answer does not settle the retry", async () => {
    const { api, answer, watch } = connected();
    const a = api.doctorPlan(selA);
    await expire(a);
    const b = api.doctorPlan(selB);
    const seen = watch(b);
    answer(0, { type: "doctorplan", plan: planFor("A") });
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, { type: "doctorplan", plan: planFor("B") });
    await expect(b).resolves.toMatchObject({ diff: "B" });
  });

  test("an apply's late result is not the retry's", async () => {
    const { api, answer, watch } = connected();
    await expire(api.doctorApply(selA));
    const b = api.doctorApply(selB);
    const seen = watch(b);
    answer(0, { type: "doctorfixed", fixed: { text: "A", code: 0 } });
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, { type: "doctorfixed", fixed: { text: "B", code: 0 } });
    await expect(b).resolves.toMatchObject({ text: "B" });
  });

  test("a junk plan's late answer does not settle the retry", async () => {
    const { api, answer, watch } = connected();
    const cleared = (freed: number) => ({ items: [], planned_bytes: 0, freed_bytes: freed });
    await expire(api.junkPlan(), 120_000);
    const b = api.junkPlan();
    const seen = watch(b);
    answer(0, { type: "junkplan", plan: cleared(1) });
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, { type: "junkplan", plan: cleared(2) });
    await expect(b).resolves.toMatchObject({ freed_bytes: 2 });
  });

  test("a drill of one project: the late answer of the first is not the second's", async () => {
    const { api, answer, watch } = connected();
    const graph = (more: number) => ({
      type: "graph",
      graph: { project: 1, nodes: [], edges: [], more },
    });
    await expire(api.drill(request));
    const b = api.drill({ ...request, limit: 1000 });
    const seen = watch(b);
    answer(0, graph(7));
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, graph(0));
    await expect(b).resolves.toMatchObject({ more: 0 });
  });

  test("a diff of one project: the late answer of the first is not the second's", async () => {
    const { api, answer, watch } = connected();
    const diff = (from: string) => ({
      type: "diff",
      project: "1",
      diff: { from, to: "working", projects: [] },
    });
    const ask = { project: "1", from: ["a"], to: null, export: null };
    await expire(api.diff(ask));
    const b = api.diff({ ...ask, from: ["b"] });
    const seen = watch(b);
    answer(0, diff("a"));
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, diff("b"));
    await expect(b).resolves.toMatchObject({ from: "b" });
  });

  test("an export's late file is not the retry's", async () => {
    const { api, answer, watch } = connected();
    const file = (name: string) => ({ type: "export", file: { name, mime: "x", data: "" } });
    await expire(api.exportGraph(exportRequest));
    const b = api.exportGraph({ ...exportRequest, format: "svg" });
    const seen = watch(b);
    answer(0, file("a.json"));
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, file("b.svg"));
    await expect(b).resolves.toMatchObject({ name: "b.svg" });
  });

  test("an import's late answer is not the retry's", async () => {
    const { api, answer, watch } = connected();
    const parsed = (name: string) => ({
      type: "imported",
      name,
      export: { projects: [], nodes: [], edges: [] },
    });
    await expire(api.importGraph({ name: "a.json", text: "{}" }));
    const b = api.importGraph({ name: "b.json", text: "{}" });
    const seen = watch(b);
    answer(0, parsed("a.json"));
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, parsed("b.json"));
    await expect(b).resolves.toMatchObject({ name: "b.json" });
  });

  test("an answer of another kind under the same id does not settle the request", async () => {
    const { api, answer, watch } = connected();
    const a = api.doctorPlan(selA);
    const seen = watch(a);
    answer(0, { type: "doctorfixed", fixed: { text: "x", code: 0 } });
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(0, { type: "doctorplan", plan: planFor("A") });
    await expect(a).resolves.toMatchObject({ diff: "A" });
  });

  test("a refusal fails only the request it names", async () => {
    const { api, answer, watch } = connected();
    const a = api.doctorPlan(selA);
    const b = api.drill(request);
    const seenB = watch(b);
    answer(0, { type: "message", text: "doctor needs an action" });
    await expect(a).rejects.toThrow("doctor needs");
    await vi.advanceTimersByTimeAsync(0);
    expect(seenB.done).toBe(false);
    answer(1, { type: "graph", graph: { project: 1, nodes: [], edges: [] } });
    await expect(b).resolves.toMatchObject({ project: 1 });
  });

  test("a refusal of an expired request does not fail the retry", async () => {
    const { api, answer, watch } = connected();
    await expire(api.doctorPlan(selA));
    const b = api.doctorPlan(selB);
    const seen = watch(b);
    answer(0, { type: "message", text: "late refusal" });
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, { type: "doctorplan", plan: planFor("B") });
    await expect(b).resolves.toMatchObject({ diff: "B" });
  });

  test("an expand refusal names its request, and a late payload is not the retry's", async () => {
    const { api, answer, watch } = connected();
    const lost = api.expand("x");
    const failed = expect(lost).rejects.toThrow("timed out");
    await vi.advanceTimersByTimeAsync(10_000);
    await failed;
    const again = api.expand("x");
    const seen = watch(again);
    answer(0, { type: "expand", id: "x", text: "late" });
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    answer(1, { type: "expand", id: "x", text: "now" });
    await expect(again).resolves.toBe("now");
  });

  test("a frame without an id settles nothing", async () => {
    const { s, api, watch } = connected();
    const a = api.doctorPlan(selA);
    const seen = watch(a);
    s.server().onFrame({ type: "doctorplan", plan: planFor("?") });
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
  });

  test("a socket that was replaced cannot settle or fail the new one's requests", async () => {
    vi.useFakeTimers();
    const sockets: { handlers: Handlers; sent: unknown[] }[] = [];
    const connect: Connect = (handlers) => {
      const socket = { handlers, sent: [] as unknown[] };
      sockets.push(socket);
      // Like the real connection, closing reports "closed" once.
      return {
        send: (m) => (socket.sent.push(m), true),
        close: () => handlers.onState("closed"),
      };
    };
    const api = createApi(queryClient, connect);
    api.open();
    const first = api.doctorPlan(selA);
    const dropped = expect(first).rejects.toThrow("connection closed");
    api.reconnect();
    await dropped;

    const second = api.doctorPlan(selB);
    const seen = { done: false };
    second.then(
      () => (seen.done = true),
      () => (seen.done = true),
    );
    const [old, now] = sockets as [(typeof sockets)[0], (typeof sockets)[0]];
    old.handlers.onFrame({
      type: "doctorplan",
      plan: planFor("A"),
      rid: ridOf(old.sent[0]),
    } as unknown as Frame);
    old.handlers.onState("closed");
    await vi.advanceTimersByTimeAsync(0);
    expect(seen.done).toBe(false);
    now.handlers.onFrame({
      type: "doctorplan",
      plan: planFor("B"),
      rid: ridOf(now.sent[0]),
    } as unknown as Frame);
    await expect(second).resolves.toMatchObject({ diff: "B" });
  });
});
