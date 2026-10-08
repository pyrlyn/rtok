// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ReactNode } from "react";
import { Icon } from "./Icon";
import { type Kind, operationIcon } from "./operations";

const tone: Record<Kind, string> = {
    success: "text-success-fg",
    info: "text-fg-muted",
    warn: "text-warn-fg",
    error: "text-delta-fg",
};

/** The answer to an action, led by its operation's icon (or the kind's when no verb matches). */
export function Result({
    verb = "",
    kind,
    children,
}: {
    verb?: string;
    kind: Kind;
    children: ReactNode;
}) {
    return (
        <div
            role={kind === "error" ? "alert" : "status"}
            className={`flex items-start gap-1.5 text-xs ${tone[kind]}`}
        >
            <Icon name={operationIcon(verb, kind)} />
            <div className="min-w-0 break-words">{children}</div>
        </div>
    );
}
