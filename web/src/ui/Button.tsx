// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { ButtonHTMLAttributes } from "react";
import { focusRing } from "./cx";
import { Icon } from "./Icon";
import { operationIcon } from "./operations";
import { Spinner } from "./Spinner";

const look = {
    outline:
        "h-7 border border-border px-2.5 text-2xs font-semibold hover:border-border-strong disabled:opacity-40",
    solid: "h-8 bg-accent px-3 text-xs font-semibold text-accent-on hover:opacity-90 disabled:opacity-60",
};

/**
 * A button that sends a request. `pending` runs from the click until the answer: the spinner
 * takes the operation's icon, the button is disabled so a second click cannot send the request
 * twice, and `aria-busy` says why.
 */
export function Button({
    verb,
    pending = false,
    variant = "outline",
    className = "",
    children,
    ...rest
}: {
    /** What the button does; picks its icon (see `operationIcon`). */
    verb?: string;
    pending?: boolean;
    variant?: keyof typeof look;
} & Omit<ButtonHTMLAttributes<HTMLButtonElement>, "type">) {
    return (
        <button
            type="button"
            {...rest}
            disabled={rest.disabled || pending}
            aria-busy={pending || undefined}
            className={`${focusRing} ${look[variant]} inline-flex cursor-pointer items-center justify-center gap-1.5 rounded-md disabled:cursor-not-allowed aria-busy:cursor-progress aria-busy:disabled:opacity-100 ${className}`}
        >
            {pending ? <Spinner size="sm" /> : verb && <Icon name={operationIcon(verb)} />}
            {children}
        </button>
    );
}
