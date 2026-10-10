// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useState } from "react";
import { useConnection } from "../../../api/query";
import { Panel } from "../../../ui/Panel";
import { CallFeed } from "./CallFeed";
import type { WindowId } from "./callsStore";
import { LiveMetrics } from "./LiveMetrics";
import { useCalls } from "./useCalls";

/**
 * What the graph tools are doing right now (T329 §8b). It exists only while the page shows it,
 * and the call stream is subscribed only while it exists: a page that does not show it asks the
 * server for nothing.
 */
export function LiveSection() {
    const calls = useCalls();
    const [window, setWindow] = useState<WindowId>("5m");
    const connection = useConnection();
    const idle = calls.store.all.calls === 0 && calls.store.running.length === 0;
    return (
        <Panel title="live graph calls" hint={connection === "open" ? "read-only" : "reconnecting"}>
            {idle && <p className="text-xs text-fg-muted">Waiting for graph calls</p>}
            <LiveMetrics
                store={calls.store}
                now={calls.now}
                window={window}
                onWindow={setWindow}
                frozen={calls.frozen}
                pending={calls.pending}
                onFreeze={calls.freeze}
            />
            <CallFeed feed={calls.store.feed} />
        </Panel>
    );
}
