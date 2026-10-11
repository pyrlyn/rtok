// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import {
    type CSSProperties,
    type KeyboardEvent,
    type ReactNode,
    useEffect,
    useRef,
    useState,
} from "react";
import { readStored, writeStored } from "../../../storage";
import { Chip } from "../../../ui/Chip";
import { focusRing } from "../../../ui/cx";

export const SPLIT_KEY = "rtok.graph.split";
export const HIDE_KEY = "rtok.graph.live.hidden";
export const MAX_KEY = "rtok.graph.max";
const MIN = 0.2;
const MAX = 0.8;
const STEP = 0.05;

const clamp = (r: number) => Math.min(MAX, Math.max(MIN, r));

function readRatio(): number {
    const r = Number(readStored(SPLIT_KEY));
    return Number.isFinite(r) && r > 0 ? clamp(r) : 0.5;
}

type Max = "explorer" | "live" | null;

function readMax(): Max {
    const v = readStored(MAX_KEY);
    return v === "explorer" || v === "live" ? v : null;
}

/**
 * Part 1 beside part 2, split by a draggable bar (stacked under 900 px, where there is no bar).
 * Part 2 is not rendered while hidden, so it holds no subscription and costs nothing.
 * Either part can be maximised to fill the area; Escape or its button restores the split.
 */
export function Split({ explorer, live }: { explorer: ReactNode; live: ReactNode }) {
    const [ratio, setRatio] = useState(readRatio);
    const [hidden, setHidden] = useState(() => readStored(HIDE_KEY) === "1");
    const [max, setMax] = useState<Max>(readMax);
    const box = useRef<HTMLDivElement>(null);
    const dragging = useRef(false);

    const set = (next: number, keep = true) => {
        setRatio(clamp(next));
        if (keep) writeStored(SPLIT_KEY, String(clamp(next)));
    };
    const move = (clientX: number) => {
        const r = box.current?.getBoundingClientRect();
        if (dragging.current && r?.width) set((clientX - r.left) / r.width, false);
    };
    const key = (e: KeyboardEvent) => {
        const d = e.key === "ArrowLeft" ? -STEP : e.key === "ArrowRight" ? STEP : 0;
        if (!d) return;
        e.preventDefault();
        set(ratio + d);
    };

    useEffect(() => {
        if (!max) return;
        const onKey = (e: globalThis.KeyboardEvent) => {
            if (e.key !== "Escape") return;
            setMax(null);
            writeStored(MAX_KEY, "");
        };
        document.addEventListener("keydown", onKey);
        return () => document.removeEventListener("keydown", onKey);
    }, [max]);

    const storeMax = (next: Max) => {
        setMax(next);
        writeStored(MAX_KEY, next ?? "");
        // Showing the live part is what maximising it means; the hide toggle then reads honestly.
        if (next === "live" && hidden) {
            setHidden(false);
            writeStored(HIDE_KEY, "0");
        }
    };

    const showExplorer = max !== "live";
    const showLive = max === "live" || (max === null && !hidden);

    return (
        <div className="flex flex-col gap-2">
            <div className="flex justify-end gap-2">
                <Chip
                    pressed={max === "explorer"}
                    onPressedChange={(on) => storeMax(on ? "explorer" : null)}
                >
                    {max === "explorer" ? "Restore explorer" : "Maximise explorer"}
                </Chip>
                <Chip
                    pressed={max === "live"}
                    onPressedChange={(on) => storeMax(on ? "live" : null)}
                >
                    {max === "live" ? "Restore live graph" : "Maximise live graph"}
                </Chip>
                <Chip
                    pressed={hidden}
                    onPressedChange={(next) => {
                        setHidden(next);
                        writeStored(HIDE_KEY, next ? "1" : "0");
                        if (next) storeMax(null);
                    }}
                >
                    {hidden ? "Show live graph" : "Hide live graph"}
                </Chip>
            </div>
            <div
                ref={box}
                style={{ "--split": `${ratio * 100}%` } as CSSProperties}
                className="flex flex-col gap-2 min-[900px]:flex-row min-[900px]:gap-0"
            >
                {showExplorer && (
                    <div
                        className={`min-w-0 ${!showLive ? "min-[900px]:flex-1" : "min-[900px]:grow-0 min-[900px]:basis-[var(--split)]"}`}
                    >
                        {explorer}
                    </div>
                )}
                {showExplorer && showLive && (
                    <div
                        role="separator"
                        aria-orientation="vertical"
                        aria-label="resize the live graph"
                        aria-valuemin={MIN * 100}
                        aria-valuemax={MAX * 100}
                        aria-valuenow={Math.round(ratio * 100)}
                        tabIndex={0}
                        onPointerDown={(e) => {
                            dragging.current = true;
                            e.currentTarget.setPointerCapture(e.pointerId);
                        }}
                        onPointerMove={(e) => move(e.clientX)}
                        onPointerUp={() => {
                            dragging.current = false;
                            writeStored(SPLIT_KEY, String(ratio));
                        }}
                        onDoubleClick={() => set(0.5)}
                        onKeyDown={key}
                        className={`${focusRing} hidden w-2 shrink-0 cursor-col-resize touch-none rounded-full bg-border hover:bg-border-strong min-[900px]:mx-1 min-[900px]:block`}
                    />
                )}
                {showLive && <div className="min-w-0 min-[900px]:flex-1">{live}</div>}
            </div>
        </div>
    );
}
