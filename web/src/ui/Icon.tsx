// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Raw SVGs so `currentColor` follows the text colour; they are our own bundled assets: the
// shared Pyrlyn UI icons plus the rtok-only ones the base does not have.
const icons = import.meta.glob<string>(
    ["@brand/node_modules/@pyrlyn/brand/base/icons/ui/*.svg", "@brand/icons/ui/*.svg"],
    // `exhaustive`: the base icons live under `brand/node_modules`, which glob skips by default.
    { query: "?raw", import: "default", eager: true, exhaustive: true },
);

const byName = new Map(
    Object.entries(icons).map(([path, svg]) => [path.split("/").pop()?.replace(".svg", ""), svg]),
);

export const hasIcon = (name: string) => byName.has(name);

export function Icon({ name }: { name: string }) {
    const svg = byName.get(name);
    return (
        <span
            aria-hidden="true"
            className="inline-flex size-4 shrink-0 [&>svg]:size-4"
            dangerouslySetInnerHTML={svg ? { __html: svg.trim() } : undefined}
        />
    );
}
