// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ReactNode } from "react";
import offlineArt from "../assets/offline.svg?raw";
import { focusRing } from "./ui/cx";

// The four states every page shares; pages pick one instead of drawing their own.
function Panel({ children, ...aria }: { children: ReactNode; role: string }) {
    return (
        <div {...aria} className="glass flex flex-col gap-1 p-4 text-xs">
            {children}
        </div>
    );
}

export function Loading() {
    return (
        // A grey skeleton did not read as "loading" (T407); `flex-1` centres the spinner in
        // `<main>`, and inside a panel the padding keeps it from collapsing.
        <div
            role="status"
            aria-busy
            className="flex flex-1 flex-col items-center justify-center gap-3 py-16 text-xs text-fg-muted"
        >
            <span
                aria-hidden="true"
                className="size-6 animate-spin rounded-full border-2 border-surface-3 border-t-accent"
            />
            Loading…
        </div>
    );
}

export function Empty({ title, hint }: { title: string; hint?: string }) {
    return (
        <Panel role="status">
            <strong>{title}</strong>
            {hint && <span className="text-fg-muted">{hint}</span>}
        </Panel>
    );
}

export function ErrorState({ message }: { message: string }) {
    return (
        <Panel role="alert">
            <strong className="text-danger-fg">Something went wrong</strong>
            <span className="text-fg-muted">{message}</span>
        </Panel>
    );
}

/** Takes the whole screen (T407): stale data next to a dead link read as live. */
export function Offline({
    connecting,
    onReconnect,
}: {
    connecting: boolean;
    onReconnect: () => void;
}) {
    return (
        // axe forbids `role="status"` on `<main>` (aria-allowed-role), so the live region is the text.
        <main className="flex min-h-screen flex-col items-center justify-center gap-4 p-6 text-center text-xs">
            <span
                aria-hidden="true"
                className="text-fg-subtle [&>svg]:h-20 [&>svg]:w-44"
                dangerouslySetInnerHTML={{ __html: offlineArt.trim() }}
            />
            <div role="status" className="flex flex-col items-center gap-4">
                <h1 className="text-base font-bold text-warn-fg">Offline</h1>
                <p className="max-w-80 text-fg-muted">The connection to rtok was lost.</p>
            </div>
            <button
                type="button"
                onClick={onReconnect}
                disabled={connecting}
                aria-busy={connecting || undefined}
                className={`${focusRing} mt-2 h-8 rounded-md bg-accent px-4 font-bold text-accent-on hover:opacity-90 disabled:opacity-60`}
            >
                {connecting ? "Reconnecting…" : "Reconnect"}
            </button>
        </main>
    );
}
