// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { Mark, MarkRows } from "../charts/Mark";
import { Mini, type MiniProps } from "./Sparkline";

export const MiniBars = (p: MiniProps) => <Mini kind="bars" {...p} />;

// One dot per plugin, lit when enabled (echoes brand/logo/rtok-mark.svg); disabled dots are coral so
// the state does not hang on brightness alone.
const tone = (p: { enabled: boolean; saves_tokens: boolean }) =>
    !p.enabled ? "bg-delta-fg/80" : p.saves_tokens ? "bg-accent-fg" : "bg-accent-fg/40";

const state = (p: { enabled: boolean; saves_tokens: boolean }) =>
    !p.enabled ? "disabled" : p.saves_tokens ? "enabled, saves tokens" : "enabled";

export function Bitset({
    items,
}: {
    items: readonly { id: string; enabled: boolean; saves_tokens: boolean }[];
}) {
    const on = items.filter((p) => p.enabled).length;
    return (
        <div
            role="img"
            aria-label={`${on} of ${items.length} plugins enabled`}
            className="ml-auto grid w-max grid-cols-6 gap-1"
        >
            {items.map((p) => (
                <Mark
                    key={p.id}
                    tip={<MarkRows title={p.id} rows={[["state", state(p)]]} />}
                    className={`size-2 rounded-full ${tone(p)}`}
                />
            ))}
        </div>
    );
}

// The mark's 4×4 bitset budget as data: each cell is 1/16 of the estimated tokens, coral cells are
// the measured cut (Δ), cyan the context kept. Cuts fill from the last cell so the grid reads like
// the mark, and the label carries the number for anyone who cannot see the colours.
export function BudgetGrid({ cut, label }: { cut: number; label: string }) {
    const cells = Number.isFinite(cut) ? Math.round(Math.min(1, Math.max(0, cut)) * 16) : 0;
    return (
        <Mark
            role="img"
            aria-label={label}
            tip={
                <MarkRows
                    title="token budget"
                    rows={[
                        ["cut", `${cells} of 16 cells`],
                        ["kept", `${16 - cells} of 16 cells`],
                    ]}
                />
            }
            className="grid size-24 shrink-0 grid-cols-4 gap-1.5 rounded-lg bg-mark p-2.5"
        >
            {Array.from({ length: 16 }, (_, i) => (
                <span
                    key={i}
                    className={`rounded-full ${i >= 16 - cells ? "bg-delta" : "bg-accent"}`}
                />
            ))}
        </Mark>
    );
}
