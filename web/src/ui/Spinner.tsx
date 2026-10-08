// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

const sizes = { sm: "size-3.5", md: "size-6" };

// The one ring of the SPA: page loads and every pending action. It takes the text colour so it
// reads on a solid accent button as well as on a panel; whoever shows it names the wait with
// `aria-busy` or a status text, so the ring itself is hidden from assistive tech.
export function Spinner({ size = "md" }: { size?: keyof typeof sizes }) {
    return (
        <span
            aria-hidden="true"
            className={`${sizes[size]} inline-block shrink-0 animate-spin rounded-full border-2 border-current/25 border-t-current motion-reduce:animate-none`}
        />
    );
}
