// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

/**
 * The detail column of a list/detail page before a row is picked. Without it the column is an
 * empty hole beside the list; below `lg` the layout is one column, where a hint would only push
 * the list down.
 */
export function DetailHint({ what }: { what: string }) {
    return (
        <p className="hidden items-center justify-center rounded-lg border border-dashed border-border-strong/60 p-6 text-center text-2xs text-fg-subtle lg:flex">
            Select a {what} to see its detail.
        </p>
    );
}
