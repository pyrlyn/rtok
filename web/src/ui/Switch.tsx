// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { focusRing } from "./cx";
import { Spinner } from "./Spinner";

// The 44px hit area is only for touch; a mouse keeps the compact control from the design.
export function Switch({
    checked,
    onCheckedChange,
    label,
    disabled = false,
    pending = false,
}: {
    checked: boolean;
    onCheckedChange?: (next: boolean) => void;
    label: string;
    disabled?: boolean;
    /** The request is on its way: `checked` stays as it was until the server answers. */
    pending?: boolean;
}) {
    return (
        <button
            type="button"
            role="switch"
            aria-checked={checked}
            aria-label={label}
            disabled={disabled || pending}
            aria-busy={pending || undefined}
            onClick={() => onCheckedChange?.(!checked)}
            className={`${focusRing} group relative inline-flex min-h-7 min-w-9 shrink-0 cursor-pointer items-center justify-center rounded-md max-md:min-h-touch max-md:min-w-touch disabled:cursor-not-allowed disabled:opacity-40 aria-busy:cursor-progress aria-busy:disabled:opacity-100`}
        >
            <span
                aria-hidden="true"
                className="relative h-5 w-9 rounded-full border border-border-strong bg-surface-3 transition-colors duration-fast ease-standard group-aria-checked:border-accent group-aria-checked:bg-accent after:absolute after:top-0.5 after:left-0.5 after:size-3.5 after:rounded-full after:bg-fg-muted after:transition-[transform,background-color] after:duration-base after:ease-emphasized group-aria-checked:after:translate-x-4 group-aria-checked:after:bg-accent-on"
            />
            {pending && (
                <span className="absolute text-accent group-aria-checked:text-accent-on">
                    <Spinner size="sm" />
                </span>
            )}
        </button>
    );
}
