// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useMemo, useState } from "react";
import { useConnection } from "../../../api/query";
import type { ProjectRow } from "../../../api/snapshot.gen";
import { Panel } from "../../../ui/Panel";
import type { DrillState } from "../drillState";
import { CallFeed } from "./CallFeed";
import { LiveGraph } from "./LiveGraph";
import { LiveMetrics } from "./LiveMetrics";
import { scopeNames } from "./scope";
import { useCalls } from "./useCalls";

/**
 * Part 2 of the graph page (T329 §8b). It exists only while visible, and the call stream is
 * subscribed only while it exists: a hidden live graph asks the server for nothing.
 */
export function LiveSection({ rows, drill }: { rows: ProjectRow[]; drill: DrillState | null }) {
    const calls = useCalls();
    // The chips are the server's windows in its order; "5 min" is the second.
    const [window, setWindow] = useState(1);
    const connection = useConnection();
    const scope = useMemo(() => scopeNames(rows, drill), [rows, drill]);
    return (
        <Panel title="live graph" hint={connection === "open" ? "read-only" : "reconnecting"}>
            <LiveGraph rows={rows} drill={drill} store={calls.view} now={calls.now} scope={scope} />
            <LiveMetrics
                view={calls.view}
                now={calls.now}
                window={window}
                onWindow={setWindow}
                frozen={calls.frozen}
                pending={calls.pending}
                onFreeze={calls.freeze}
            />
            <CallFeed feed={calls.view.feed} scope={scope} />
        </Panel>
    );
}
