// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useEffect, useState } from "react";
import { useConnection, usePaused, usePauseToggle, useSnapshot } from "./api/query";
import type { ConnectionState } from "./api/ws";
import { ago } from "./pages/format";
import { focusRing } from "./ui/cx";
import { Icon } from "./ui/Icon";
import { Pill, type PillTone } from "./ui/Pill";

// The label says the state; the tone only repeats it.
const linkTone: Record<ConnectionState, PillTone> = {
    open: "ok",
    connecting: "info",
    closed: "fail",
};

/** "updated 3s ago"; `now` is a parameter so tests do not depend on the clock. */
export const ageLabel = (updatedAtMs: number, nowMs: number): string =>
    `updated ${ago(updatedAtMs / 1000, nowMs / 1000)}`;

function useNow(everyMs: number): number {
    const [now, setNow] = useState(Date.now);
    useEffect(() => {
        const id = setInterval(() => setNow(Date.now()), everyMs);
        return () => clearInterval(id);
    }, [everyMs]);
    return now;
}

export function LiveStatusView({
    link,
    updatedAt,
    now,
    paused,
    onPausedChange,
}: {
    link: ConnectionState;
    /** Epoch ms of the snapshot on screen; absent until the first one arrives. */
    updatedAt?: number;
    now: number;
    paused: boolean;
    onPausedChange: (paused: boolean) => void;
}) {
    return (
        <>
            {updatedAt !== undefined && (
                <span className="text-2xs whitespace-nowrap text-fg-subtle tabular-nums">
                    {ageLabel(updatedAt, now)}
                </span>
            )}
            <Pill tone={linkTone[link]} dot>
                {link === "open" ? "live" : link}
            </Pill>
            {paused && (
                <Pill tone="warn" dot>
                    paused
                </Pill>
            )}
            {/* Not role=status: the loading screen already owns that role. The age ticks every second, so only the pause state is announced. */}
            <span aria-live="polite" className="sr-only">
                {paused ? "Live updates paused" : "Live updates on"}
            </span>
            <button
                type="button"
                onClick={() => onPausedChange(!paused)}
                disabled={updatedAt === undefined}
                aria-pressed={paused}
                aria-label="Pause live updates"
                title={paused ? "Resume live updates" : "Pause live updates"}
                className={`${focusRing} grid size-8 place-items-center rounded-md border border-border text-fg-muted transition-colors duration-fast hover:border-border-strong hover:text-fg disabled:opacity-50 aria-pressed:border-warn/40 aria-pressed:bg-warn/15 aria-pressed:text-warn-fg`}
            >
                <Icon name={paused ? "play" : "pause"} />
            </button>
        </>
    );
}

export function LiveStatus() {
    const link = useConnection();
    const { dataUpdatedAt, data } = useSnapshot();
    const paused = usePaused();
    const setPaused = usePauseToggle();
    // Its own clock: the one-second tick re-renders only this strip, not the whole shell.
    const now = useNow(1000);
    return (
        <LiveStatusView
            link={link}
            updatedAt={data ? dataUpdatedAt : undefined}
            now={now}
            paused={paused}
            onPausedChange={setPaused}
        />
    );
}
