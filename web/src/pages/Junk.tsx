// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The `junk` card of the Hosts page (T330.7): what `rtok agents junk list` totals, and the
// "Clear safe junk" button. The server plans and removes (`agents junk clear`'s own plan,
// re-checks and refusals); this card shows the dry run and sends the delete message only
// from the explicit confirm.
import { useReducer } from "react";
import { useJunkApi } from "../api/query";
import type { Cleared, JunkCard } from "../api/snapshot.gen";
import { Loading } from "../states";
import { Button } from "../ui/Button";
import { Panel } from "../ui/Panel";
import { Pill } from "../ui/Pill";
import { Result } from "../ui/Result";
import { bytes } from "./format";
import { groupPlan, initialJunk, junkReducer, removable, type JunkState } from "./junkState";

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

export function Junk({ card }: { card: JunkCard | null }) {
    const api = useJunkApi();
    const [state, dispatch] = useReducer(junkReducer, initialJunk);

    const plan = () => {
        dispatch({ type: "plan" });
        api.junkPlan().then(
            (p) => dispatch({ type: "planned", plan: p }),
            (e) => dispatch({ type: "failed", error: message(e) }),
        );
    };
    const confirm = () => {
        dispatch({ type: "applying" });
        api.junkApply(removable(state.plan).map((i) => i.path)).then(
            (result) => dispatch({ type: "applied", result }),
            (e) => dispatch({ type: "failed", error: message(e) }),
        );
    };

    return (
        <Panel
            title="junk"
            hint="what `rtok agents junk clear` would remove; nothing is deleted before you confirm"
        >
            {card ? <Totals card={card} /> : <Loading />}
            <Actions state={state} dispatch={dispatch} plan={plan} confirm={confirm} />
        </Panel>
    );
}

function Totals({ card }: { card: JunkCard }) {
    return (
        <div className="flex flex-col gap-3">
            {card.agents.map((a) => (
                <section key={a.name} aria-label={`junk of ${a.name}`}>
                    <div className="flex flex-wrap items-baseline gap-2 text-xs font-semibold">
                        {a.name}
                        <span className="text-2xs font-normal text-fg-muted">
                            {bytes(a.total_bytes)} in its folders
                        </span>
                    </div>
                    <ul className="text-2xs text-fg-muted">
                        {a.kinds.map((k) => (
                            <li key={k.kind} className="flex items-center gap-2">
                                <span className="w-24 shrink-0">{k.kind}</span>
                                <Pill tone={k.class === "safe" ? "ok" : "muted"}>{k.class}</Pill>
                                <span>
                                    {k.items} {k.items === 1 ? "item" : "items"}, {bytes(k.size_bytes)}
                                </span>
                            </li>
                        ))}
                    </ul>
                    <p className="text-2xs">
                        Freed by <code>clear</code>: {bytes(a.freed_default_bytes)} · with{" "}
                        <code>--include review</code>: {bytes(a.freed_review_bytes)}
                    </p>
                </section>
            ))}
            <p className="border-t border-border/60 pt-2 text-xs font-semibold">
                total: {bytes(card.total_bytes)} in folders · Freed by <code>clear</code>:{" "}
                {bytes(card.freed_default_bytes)} · with <code>--include review</code>:{" "}
                {bytes(card.freed_review_bytes)}
            </p>
        </div>
    );
}

function Actions({
    state,
    dispatch,
    plan,
    confirm,
}: {
    state: JunkState;
    dispatch: (a: Parameters<typeof junkReducer>[1]) => void;
    plan: () => void;
    confirm: () => void;
}) {
    const { phase } = state;
    const error = state.error && (
        <Result verb="remove" kind="error">
            {state.error}
        </Result>
    );
    if (phase === "done" && state.result) return <Done result={state.result} dispatch={dispatch} />;
    if (!state.plan)
        return (
            <div className="flex flex-col gap-2">
                {error}
                <div>
                    <Button verb="remove" pending={phase === "planning"} onClick={plan}>
                        Clear safe junk
                    </Button>
                </div>
            </div>
        );
    const items = removable(state.plan);
    const busy = phase === "applying";
    return (
        <div className="flex flex-col gap-3">
            <Plan plan={state.plan} />
            {error}
            <div className="flex items-center gap-2">
                {phase === "confirming" || busy ? (
                    <>
                        <span className="text-xs">
                            Delete {items.length} {items.length === 1 ? "item" : "items"} (
                            {bytes(state.plan.planned_bytes)})?
                        </span>
                        <Button verb="remove" pending={busy} onClick={confirm}>
                            Confirm
                        </Button>
                        <Button disabled={busy} onClick={() => dispatch({ type: "cancel" })}>
                            Cancel
                        </Button>
                    </>
                ) : (
                    <>
                        <Button
                            verb="remove"
                            disabled={items.length === 0}
                            onClick={() => dispatch({ type: "ask" })}
                        >
                            Clear {items.length} {items.length === 1 ? "item" : "items"}
                        </Button>
                        <Button onClick={() => dispatch({ type: "cancel" })}>Cancel</Button>
                    </>
                )}
            </div>
        </div>
    );
}

function Plan({ plan }: { plan: Cleared }) {
    const groups = groupPlan(plan.items);
    if (plan.items.length === 0)
        return (
            <p role="status" className="text-xs text-fg-muted">
                Nothing to clear.
            </p>
        );
    return (
        <div aria-label="junk plan" role="region" className="flex flex-col gap-2">
            {groups.map((g) => (
                <details key={`${g.agent}/${g.kind}`} className="text-2xs">
                    <summary className="cursor-pointer text-xs">
                        <span className="font-semibold">
                            {g.agent} {g.kind}
                        </span>
                        : {g.items.length} {g.items.length === 1 ? "item" : "items"}, {bytes(g.bytes)}{" "}
                        planned
                    </summary>
                    <ul className="mt-1 flex flex-col gap-1 text-fg-muted">
                        {g.items.map((i) => (
                            <li key={i.path} className="break-all">
                                {i.path} · {bytes(i.bytes)} · {i.reason}
                                {!i.planned && <span className="text-warn-fg"> · kept: {i.note}</span>}
                            </li>
                        ))}
                    </ul>
                </details>
            ))}
            <p className="text-xs">
                Dry run: {bytes(plan.planned_bytes)} to free, nothing changed.
            </p>
        </div>
    );
}

function Done({
    result,
    dispatch,
}: {
    result: Cleared;
    dispatch: (a: Parameters<typeof junkReducer>[1]) => void;
}) {
    const failed = result.items.filter((i) => i.failed);
    return (
        <div className="flex flex-col gap-3">
            <Result verb="remove" kind={failed.length === 0 ? "success" : "warn"}>
                Freed {bytes(result.freed_bytes)} of {bytes(result.planned_bytes)} planned
                {failed.length > 0 && `; ${failed.length} not removed`}
            </Result>
            {failed.map((i) => (
                <p key={i.path} className="text-2xs break-all text-warn-fg">
                    {i.path}: {i.note}
                </p>
            ))}
            <div>
                <Button onClick={() => dispatch({ type: "reset" })}>Done</Button>
            </div>
        </div>
    );
}
