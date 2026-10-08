// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import offlineArt from "@brand/illustrations/offline.svg?raw";
import { Button } from "./ui/Button";
import { Icon } from "./ui/Icon";
import { Spinner } from "./ui/Spinner";

// The four states every page shares; pages pick one instead of drawing their own.

export function Loading() {
    return (
        // A grey skeleton did not read as "loading" (T407); `flex-1` centres the spinner in
        // `<main>`, and inside a panel the padding keeps it from collapsing.
        <div
            role="status"
            aria-busy
            className="flex flex-1 flex-col items-center justify-center gap-3 py-16 text-xs text-fg-muted"
        >
            <span className="text-accent">
                <Spinner />
            </span>
            Loading…
        </div>
    );
}

export function Empty({ title, hint }: { title: string; hint?: string }) {
    return (
        // Dashed and unfilled: an empty block sits inside panels as often as on its own, and a
        // second glass layer there reads as a card with nothing in it.
        <div
            role="status"
            className="flex flex-col items-center gap-1 rounded-lg border border-dashed border-border px-4 py-6 text-center text-xs"
        >
            <span className="text-fg-subtle">
                <Icon name="info" />
            </span>
            <strong>{title}</strong>
            {hint && <span className="max-w-prose text-fg-muted">{hint}</span>}
        </div>
    );
}

export function ErrorState({ message }: { message: string }) {
    return (
        <div
            role="alert"
            className="flex items-start gap-2 rounded-lg border border-danger/50 bg-danger/10 p-4 text-xs"
        >
            <span className="text-danger-fg">
                <Icon name="error" />
            </span>
            <div className="flex min-w-0 flex-col gap-1">
                <strong className="text-danger-fg">Something went wrong</strong>
                <span className="break-words text-fg-muted">{message}</span>
            </div>
        </div>
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
        <main className="flex min-h-screen flex-col items-center justify-center p-6 text-center text-xs">
            <div className="glass flex w-full max-w-sm flex-col items-center gap-4 p-8">
                <span
                    aria-hidden="true"
                    className="text-fg-subtle [&>svg]:h-20 [&>svg]:w-44"
                    dangerouslySetInnerHTML={{ __html: offlineArt.trim() }}
                />
                <div role="status" className="flex flex-col items-center gap-2">
                    <h1 className="text-base font-bold text-warn-fg">Offline</h1>
                    <p className="max-w-80 text-fg-muted">The connection to rtok was lost.</p>
                </div>
                <Button
                    variant="solid"
                    pending={connecting}
                    onClick={onReconnect}
                    className="mt-2 max-md:h-touch"
                >
                    {connecting ? "Reconnecting…" : "Reconnect"}
                </Button>
            </div>
        </main>
    );
}
