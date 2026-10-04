// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ReactNode } from "react";

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

export function Offline() {
    return (
        <Panel role="status">
            <strong className="text-warn-fg">Offline</strong>
            <span className="text-fg-muted">
                The connection to rtok was lost. The last data stays on screen and updates resume
                when it returns.
            </span>
        </Panel>
    );
}
