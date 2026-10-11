// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import { DataProvider } from "../api/query";
import type { Snapshot } from "../api/snapshot.gen";
import { answering, type Connect, type Frame, type Outgoing } from "../api/ws";
import { createAppRouter } from "../router";

export const serving =
    (snapshot: Snapshot): Connect =>
    (h) => {
        h.onState("open");
        h.onFrame({ type: "snapshot", snapshot });
        return { send: () => true, close: () => {} };
    };

// The request each answer replies to.
const ASKS: Partial<Record<Frame["type"], string>> = {
    expand: "expand",
    doctorplan: "doctor",
    doctorfixed: "doctor",
    junkplan: "junk",
    junkcleared: "junk",
    graph: "graph",
    diff: "diff",
    export: "export",
    imported: "import",
};

/** The id of the newest request sent, of one kind when `key` names it; calls are no request. */
const latest = (sent: Outgoing[], key?: string) =>
    sent.findLast((m) => !("calls" in m) && (key === undefined || key in m))?.rid;

/** A socket the test drives: it records what the page sends and pushes the frames it likes. */
export function wire(first: Snapshot) {
    const sent: Outgoing[] = [];
    let push: (f: Frame) => void = () => {};
    const connect: Connect = (h) => {
        push = h.onFrame;
        h.onState("open");
        h.onFrame({ type: "snapshot", snapshot: first });
        return { send: (m) => (sent.push(m), true), close: () => {} };
    };
    return {
        connect,
        sent,
        push: (snapshot: Snapshot) => push({ type: "snapshot", snapshot }),
        // A refusal answers the latest request; a write has no id, so it gets none.
        message: (text: string) => push(answering({ type: "message", text }, latest(sent))),
        // The server echoes the id of the request an answer replies to: the latest of its kind.
        frame: (f: Frame) => push(answering(f, latest(sent, ASKS[f.type]))),
    };
}

export function mount(connect: Connect, path: string) {
    return mountRouted(connect, path).view;
}

/** `mount` plus the router, for a test that reads the URL or steps through history. */
export function mountRouted(connect: Connect, path: string) {
    const router = createAppRouter(createMemoryHistory({ initialEntries: [path] }));
    const view = render(
        <DataProvider connect={connect}>
            <RouterProvider router={router} />
        </DataProvider>,
    );
    return { view, router };
}
