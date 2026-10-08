// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import { DataProvider } from "../api/query";
import type { ClientMessage, Snapshot } from "../api/snapshot.gen";
import type { Connect, Frame } from "../api/ws";
import { createAppRouter } from "../router";

export const serving =
    (snapshot: Snapshot): Connect =>
    (h) => {
        h.onState("open");
        h.onFrame({ type: "snapshot", snapshot });
        return { send: () => true, close: () => {} };
    };

/** A socket the test drives: it records what the page sends and pushes the frames it likes. */
export function wire(first: Snapshot) {
    const sent: ClientMessage[] = [];
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
        message: (text: string) => push({ type: "message", text }),
    };
}

export function mount(connect: Connect, path: string) {
    const router = createAppRouter(createMemoryHistory({ initialEntries: [path] }));
    return render(
        <DataProvider connect={connect}>
            <RouterProvider router={router} />
        </DataProvider>,
    );
}
