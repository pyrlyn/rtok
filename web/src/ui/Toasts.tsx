// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useEffect } from "react";
import { Button } from "./Button";
import { Result } from "./Result";

export interface ToastItem {
    id: number;
    /** `fail` is a problem that began, `ok` one that ended. */
    tone: "fail" | "ok";
    text: string;
}

/** Long enough to read a line, short enough that a flapping project does not pile them up. */
export const TOAST_MS = 8000;

function Toast({ item, onDismiss }: { item: ToastItem; onDismiss(id: number): void }) {
    useEffect(() => {
        const t = setTimeout(() => onDismiss(item.id), TOAST_MS);
        return () => clearTimeout(t);
    }, [item.id, onDismiss]);
    return (
        <li className="glass flex items-start gap-3 rounded-md border border-border-strong px-3 py-2 shadow-e3">
            <Result kind={item.tone === "fail" ? "error" : "success"}>{item.text}</Result>
            <Button className="ml-auto shrink-0" onClick={() => onDismiss(item.id)}>
                dismiss
            </Button>
        </li>
    );
}

/** A corner stack of short notices that go away by themselves; the region stays so a screen reader hears each new one. */
export function Toasts({ items, onDismiss }: { items: ToastItem[]; onDismiss(id: number): void }) {
    return (
        <ul
            aria-label="notifications"
            aria-live="polite"
            className="pointer-events-none fixed right-4 bottom-4 z-50 flex max-w-sm flex-col gap-2 *:pointer-events-auto"
        >
            {items.map((item) => (
                <Toast key={item.id} item={item} onDismiss={onDismiss} />
            ))}
        </ul>
    );
}
