// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// TanStack Query wiring for the `/ws` stream (T310.3). The server pushes, so the snapshot
// lives in the query cache (written by `setQueryData`, never fetched) and every page reads
// it through `useSnapshot`.
import {
    keepPreviousData,
    QueryClient,
    QueryClientProvider,
    skipToken,
    useMutation,
    useMutationState,
    useQuery,
} from "@tanstack/react-query";
import { createContext, useContext, useEffect, useMemo, useRef, type ReactNode } from "react";
import type {
    CallsView,
    Cleared,
    ClientMessage,
    DiffReport,
    DiffRequest,
    DrillGraph,
    DrillRequest,
    Export,
    ExportFile,
    ExportRequest,
    Fixed,
    ImportRequest,
    Plan,
    ProjectRequest,
    Selection,
    SetRequest,
    Snapshot,
} from "./snapshot.gen";
import type { Connect, Connection, ConnectionState, Frame } from "./ws";

export const snapshotKey = ["snapshot"] as const;
export const connectionKey = ["connection"] as const;
export const pausedKey = ["paused"] as const;

export const EXPAND_TIMEOUT_MS = 10_000;
// A fix writes files and backs each one up first, so it gets longer than a read.
export const DOCTOR_TIMEOUT_MS = 30_000;
// Planning junk walks every installed agent's folders (up to 10 s each) before it answers.
export const JUNK_TIMEOUT_MS = 120_000;
// A project select can index the project before the server answers.
export const WRITE_TIMEOUT_MS = 30_000;

/** A saved graph export the server parsed (T329.40); the page shows it read-only. */
export interface Imported {
    name: string;
    export: Export;
}

type Answer = Plan | Fixed | Cleared | DrillGraph | DiffReport | ExportFile | Imported;

export interface Api {
    open(): void;
    close(): void;
    reconnect(): void;
    pause(): void;
    resume(): void;
    expand(id: string): Promise<string>;
    set(request: SetRequest): Promise<void>;
    project(request: ProjectRequest): Promise<void>;
    doctorPlan(selection: Selection): Promise<Plan>;
    doctorApply(selection: Selection): Promise<Fixed>;
    junkPlan(): Promise<Cleared>;
    junkApply(paths: string[]): Promise<Cleared>;
    drill(request: DrillRequest): Promise<DrillGraph>;
    /** What a change did to a project's graph (T329.35); the old side is a ref or export text. */
    diff(request: DiffRequest): Promise<DiffReport>;
    /** The file `rtok graph export` writes for the same arguments (T329.40). */
    exportGraph(request: ExportRequest): Promise<ExportFile>;
    /** A saved export's text, parsed by the server; nothing is written (T329.40). */
    importGraph(request: ImportRequest): Promise<Imported>;
    /** Starts the graph call stream (T329.26) for as long as a listener is registered; returns the stop. */
    calls(listener: (view: CallsView) => void): () => void;
}

// `set` and `project` get no reply of their own: the server answers a write with the next
// snapshot, or with a message when it refuses. So a write is settled by whichever comes first.
interface PendingWrite {
    resolve(): void;
    reject(error: Error): void;
}

// Every request carries an id the server echoes in its answer, a refusal included (T623). An
// answer settles only the request with that id and kind, so one that outlives its timeout, or
// arrives on a replaced socket, cannot be taken for the answer to the next request.
interface Pending {
    rid: string;
    kind:
        | "expand"
        | "doctorplan"
        | "doctorfixed"
        | "junkplan"
        | "junkcleared"
        | "graph"
        | "diff"
        | "export"
        | "imported";
    /** For `expand`: the archive id the answer must name as well. */
    archiveId?: string;
    resolve(answer: Answer | string): void;
    reject(error: Error): void;
}

export function createApi(
    queryClient: QueryClient,
    connect: Connect,
    expandTimeoutMs = EXPAND_TIMEOUT_MS,
): Api {
    let connection: Connection | null = null;
    let pending: Pending[] = [];
    let pendingWrites: PendingWrite[] = [];
    // While paused the socket keeps reading, but frames fold into `held` and the cache keeps the
    // snapshot on screen, so `dataUpdatedAt` stays the age of what the reader sees.
    let paused = false;
    let held: Snapshot | undefined;
    const callListeners = new Set<(view: CallsView) => void>();
    const subscribeCalls = (subscribe: boolean) => connection?.send({ calls: { subscribe } });

    const writeSnapshot = (update: (prev: Snapshot | undefined) => Snapshot | undefined) => {
        if (paused) held = update(held ?? queryClient.getQueryData<Snapshot>(snapshotKey));
        else queryClient.setQueryData<Snapshot>(snapshotKey, update);
    };

    const setPaused = (value: boolean) => {
        paused = value;
        queryClient.setQueryData<boolean>(pausedKey, value);
    };

    const rejectWrites = (reason: string) => {
        const failed = pendingWrites;
        pendingWrites = [];
        for (const p of failed) p.reject(new Error(reason));
    };

    const rejectAll = (reason: string) => {
        const failed = pending;
        pending = [];
        for (const p of failed) p.reject(new Error(reason));
        rejectWrites(reason);
    };

    // Ids only need to differ within this api: no answer survives its socket.
    let lastRid = 0;
    // Each socket's handlers carry its number; once a newer socket exists the old one's late
    // frames and state changes are dropped instead of settling the new socket's requests.
    let generation = 0;

    const write = (message: ClientMessage) =>
        new Promise<void>((resolve, reject) => {
            const settle = (done: () => void) => () => {
                clearTimeout(timer);
                done();
            };
            const entry: PendingWrite = {
                resolve: settle(resolve),
                reject: (error) => settle(() => reject(error))(),
            };
            const timer = setTimeout(() => {
                pendingWrites = pendingWrites.filter((p) => p !== entry);
                reject(new Error("the server did not answer"));
            }, WRITE_TIMEOUT_MS);
            pendingWrites.push(entry);
            if (!connection?.send(message)) {
                pendingWrites = pendingWrites.filter((p) => p !== entry);
                entry.reject(new Error("not connected"));
            }
        });

    const settleWrites = () => {
        const done = pendingWrites;
        pendingWrites = [];
        for (const p of done) p.resolve();
    };

    const settle = (
        kind: Pending["kind"],
        rid: string | null | undefined,
        answer: Answer | string,
        archiveId?: string,
    ) => {
        const i = pending.findIndex(
            (p) =>
                p.rid === rid &&
                p.kind === kind &&
                (p.archiveId === undefined || p.archiveId === archiveId),
        );
        if (i < 0) return;
        const [entry] = pending.splice(i, 1);
        entry?.resolve(answer);
    };

    const ask = <T extends Answer | string>(
        kind: Pending["kind"],
        message: ClientMessage,
        timeoutMs: number,
        archiveId?: string,
    ) =>
        new Promise<T>((resolve, reject) => {
            const rid = String(++lastRid);
            const entry: Pending = {
                rid,
                kind,
                archiveId,
                resolve: (answer) => {
                    clearTimeout(timer);
                    resolve(answer as T);
                },
                reject: (error) => {
                    clearTimeout(timer);
                    reject(error);
                },
            };
            const timer = setTimeout(() => {
                pending = pending.filter((p) => p !== entry);
                reject(
                    new Error(archiveId ? `expand ${archiveId} timed out` : `${kind} timed out`),
                );
            }, timeoutMs);
            pending.push(entry);
            if (!connection?.send({ ...message, rid })) {
                pending = pending.filter((p) => p !== entry);
                entry.reject(new Error("not connected"));
            }
        });

    const onFrame = (frame: Frame) => {
        switch (frame.type) {
            case "snapshot":
                // A paused page still settles writes: the server applied them, only the view waits.
                writeSnapshot(() => frame.snapshot);
                settleWrites();
                return;
            case "snapshot_error":
                // A failed tick has no page data; keep the last good one and surface the error.
                writeSnapshot((prev) => prev && { ...prev, error: frame.error });
                settleWrites();
                return;
            case "expand":
                settle("expand", frame.rid, frame.text, frame.id);
                return;
            case "doctorplan":
                settle("doctorplan", frame.rid, frame.plan);
                return;
            case "doctorfixed":
                settle("doctorfixed", frame.rid, frame.fixed);
                return;
            case "junkplan":
                settle("junkplan", frame.rid, frame.plan);
                return;
            case "junkcleared":
                settle("junkcleared", frame.rid, frame.cleared);
                return;
            case "graph":
                settle("graph", frame.rid, frame.graph);
                return;
            case "diff":
                settle("diff", frame.rid, frame.diff);
                return;
            case "export":
                settle("export", frame.rid, frame.file);
                return;
            case "imported":
                settle("imported", frame.rid, { name: frame.name, export: frame.export });
                return;
            case "calls":
                for (const listener of callListeners) listener(frame.calls);
                return;
            case "message":
                // A refusal names the request it answers; one that names none is a write's
                // (`set` and `project` have no answer of their own), and one that names an
                // expired request is ignored.
                if (frame.rid) rejectRequest(frame.rid, frame.text);
                else rejectWrites(frame.text);
        }
    };

    const rejectRequest = (rid: string, reason: string) => {
        const i = pending.findIndex((p) => p.rid === rid);
        if (i < 0) return;
        const [entry] = pending.splice(i, 1);
        entry?.reject(new Error(reason));
    };

    const onState = (state: ConnectionState) => {
        queryClient.setQueryData<ConnectionState>(connectionKey, state);
        // A new socket starts with no subscription: the stream resumes with the listeners still registered.
        if (state === "open" && callListeners.size) subscribeCalls(true);
        if (state === "closed") rejectAll("connection closed");
    };

    const attach = () => {
        const mine = ++generation;
        return connect({
            onState: (state) => mine === generation && onState(state),
            onFrame: (frame) => mine === generation && onFrame(frame),
        });
    };

    return {
        open() {
            connection ??= attach();
        },
        close() {
            connection?.close();
            connection = null;
        },
        // The socket's own backoff can be seconds away; the offline screen's button skips it.
        reconnect() {
            connection?.close();
            // The old socket's requests can never be answered on the new one.
            rejectAll("connection closed");
            connection = attach();
        },
        // There is nothing to freeze before the first snapshot, and a freeze with no frame would
        // hide the first page behind a pause the reader cannot see.
        pause() {
            if (queryClient.getQueryData(snapshotKey)) setPaused(true);
        },
        resume() {
            setPaused(false);
            const next = held;
            held = undefined;
            if (next) queryClient.setQueryData<Snapshot>(snapshotKey, next);
        },
        expand: (id) => ask<string>("expand", { expand: id }, expandTimeoutMs, id),
        set: (request) => write({ set: request }),
        project: (request) => write({ project: request }),
        doctorPlan: (selection) =>
            ask<Plan>("doctorplan", { doctor: { action: "plan", selection } }, DOCTOR_TIMEOUT_MS),
        doctorApply: (selection) =>
            ask<Fixed>(
                "doctorfixed",
                { doctor: { action: "apply", selection } },
                DOCTOR_TIMEOUT_MS,
            ),
        junkPlan: () =>
            ask<Cleared>("junkplan", { junk: { action: "plan", paths: [] } }, JUNK_TIMEOUT_MS),
        junkApply: (paths) =>
            ask<Cleared>("junkcleared", { junk: { action: "apply", paths } }, JUNK_TIMEOUT_MS),
        drill: (request) => ask<DrillGraph>("graph", { graph: request }, DOCTOR_TIMEOUT_MS),
        diff: (request) => ask<DiffReport>("diff", { diff: request }, DOCTOR_TIMEOUT_MS),
        exportGraph: (request) => ask<ExportFile>("export", { export: request }, DOCTOR_TIMEOUT_MS),
        importGraph: (request) => ask<Imported>("imported", { import: request }, DOCTOR_TIMEOUT_MS),
        calls(listener) {
            callListeners.add(listener);
            if (callListeners.size === 1) subscribeCalls(true);
            return () => {
                if (callListeners.delete(listener) && callListeners.size === 0)
                    subscribeCalls(false);
            };
        },
    };
}

const ApiContext = createContext<Api | null>(null);

export function DataProvider({ connect, children }: { connect: Connect; children: ReactNode }) {
    const queryClient = useMemo(() => new QueryClient(), []);
    const api = useMemo(() => createApi(queryClient, connect), [queryClient, connect]);
    useEffect(() => {
        api.open();
        return () => api.close();
    }, [api]);
    return (
        <QueryClientProvider client={queryClient}>
            <ApiContext value={api}>{children}</ApiContext>
        </QueryClientProvider>
    );
}

function useApi(): Api {
    const api = useContext(ApiContext);
    if (!api) throw new Error("DataProvider is missing");
    return api;
}

// `skipToken` makes these cache-only queries: no fetch, no retry, just what the socket wrote.
const pushed = (queryKey: readonly string[]) =>
    ({ queryKey, queryFn: skipToken, staleTime: Infinity }) as const;

export const useSnapshot = () => useQuery<Snapshot>(pushed(snapshotKey));

export const useConnection = (): ConnectionState =>
    useQuery<ConnectionState>(pushed(connectionKey)).data ?? "connecting";

export const usePaused = (): boolean => useQuery<boolean>(pushed(pausedKey)).data ?? false;

export function usePauseToggle(): (paused: boolean) => void {
    const api = useApi();
    return (paused) => (paused ? api.pause() : api.resume());
}

export function useReconnect(): () => void {
    const api = useApi();
    return () => api.reconnect();
}

// A write is pending from the click until the server answers. `inFlight` lists every request
// still waiting, so each control can show its own spinner while another is being answered;
// `error` is the last failure and clears when the next write starts.
function useWrite<R>(name: string, send: (request: R) => Promise<void>) {
    const { mutate, error } = useMutation({ mutationKey: [name], mutationFn: send });
    const inFlight = useMutationState({
        filters: { mutationKey: [name], status: "pending" },
        select: (m) => m.state.variables as R,
    });
    return { mutate, error, inFlight };
}

export function useSetMutation() {
    const api = useApi();
    return useWrite<SetRequest>("set", (request) => api.set(request));
}

export function useProjectMutation() {
    const api = useApi();
    return useWrite<ProjectRequest>("project", (request) => api.project(request));
}

export function useDoctorApi(): Pick<Api, "doctorPlan" | "doctorApply"> {
    return useApi();
}

export function useJunkApi(): Pick<Api, "junkPlan" | "junkApply"> {
    return useApi();
}

export function useGraphFileApi(): Pick<Api, "exportGraph" | "importGraph"> {
    return useApi();
}

/**
 * One drill-down frame (T329.22). `version` is whatever should make the page ask again, such as
 * the project's index numbers; the last frame stays on screen meanwhile so the layout and the
 * zoom survive an update.
 */
export function useDrill(request: DrillRequest, version: readonly unknown[]) {
    const api = useApi();
    return useQuery({
        queryKey: ["drill", request, version],
        queryFn: () => api.drill(request),
        placeholderData: keepPreviousData,
        staleTime: Infinity,
        retry: false,
    });
}

/**
 * The diff of one project (T329.35). `tag` stands for the export text, which is too long for a
 * query key; the report is kept while the next one loads, as the drill-down frame is.
 */
export function useDiff(request: DiffRequest | null, tag: readonly unknown[]) {
    const api = useApi();
    return useQuery({
        queryKey: ["diff", request && { ...request, export: null }, tag],
        queryFn: () => api.diff(request!),
        enabled: request !== null,
        placeholderData: keepPreviousData,
        staleTime: Infinity,
        retry: false,
    });
}

export function useExpandMutation() {
    const api = useApi();
    return useMutation({ mutationFn: (id: string) => api.expand(id) });
}

/** Delivers every calls frame while mounted; the socket subscribes only for as long as a page listens. */
export function useCallStream(onView: (view: CallsView) => void) {
    const api = useApi();
    const latest = useRef(onView);
    latest.current = onView;
    useEffect(() => api.calls((view) => latest.current(view)), [api]);
}
