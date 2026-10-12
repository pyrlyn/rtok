// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// A mocked machine for the doctor fix flow (T331.12): the same request/answer shape as the
// server, over three fixed findings, so the page and its tests run without a host. It mirrors
// the server's rules (shared files start unselected, a keep swap moves the removal to the other
// copy) but removes nothing real; `apply` only reports what it would have written. Like the
// server it refuses a write that carries no preview evidence, and answers a write whose files
// changed since the preview (T576.2) with the new plan instead of a result.
import type { Fixed, Item, Plan, Ref, Selection } from "./snapshot.gen";

const USER = "/home/me/.claude/settings.json";
const PROJECT = "/work/app/.claude/settings.json";
const BROKEN_PATH = "hooks.Stop[0].hooks[0]";
const DUP_PATH = "hooks.PreToolUse[0].hooks[0]";

const base = {
  agent: "claude",
};

const has = (refs: Ref[], source: string, path: string) =>
  refs.some((r) => r.source === source && r.path === path);

const brokenIn = (source: string, shared: boolean): Item => ({
  ...base,
  source,
  path: BROKEN_PATH,
  kind: "broken-hook",
  label: "/opt/gone/stop.sh",
  detail: "the command does not exist",
  kept_in: null,
  can_keep: false,
  shared,
  selected: !shared,
});

export interface MockMachine {
  plan(selection: Selection): Plan;
  apply(selection: Selection): Fixed;
}

export interface MockOptions {
  /** How many applies find the files changed since the preview, before one goes through. */
  stale?: number;
}

export function mockMachine({ stale = 0 }: MockOptions = {}): MockMachine {
  let version = 1;
  let driftsLeft = stale;
  // One evidence file stands for "what the diff showed"; a drift bumps its version.
  const evidence = () => ({ files: [{ source: USER, version: `v${version}`, entries: [] }] });
  const items = (sel: Selection): Item[] => {
    // The last swap names the copy that stays; the other one is the removable entry.
    const kept = sel.keep.at(-1)?.source === USER ? USER : PROJECT;
    const gone = kept === USER ? PROJECT : USER;
    const shared = gone === PROJECT;
    const dup: Item = {
      ...base,
      source: gone,
      path: DUP_PATH,
      kind: "duplicate-hook",
      label: "rtok hook pre-tool",
      detail: "also registered in another file",
      kept_in: kept,
      can_keep: true,
      shared,
      selected: !shared,
    };
    return [brokenIn(USER, false), brokenIn(PROJECT, true), dup].map((i) => ({
      ...i,
      // After a swap the defaults are recomputed, so a toggle flips the new default.
      selected: has(sel.toggled, i.source, i.path) ? !i.selected : i.selected,
    }));
  };
  const chosen = (sel: Selection) => items(sel).filter((i) => i.selected);
  const plan = (sel: Selection): Plan => {
    const picked = chosen(sel);
    const files = [...new Set(picked.map((i) => i.source))];
    const diff = files
      .map(
        (f) =>
          `--- ${f}\n+++ ${f}\n${picked
            .filter((i) => i.source === f)
            .map((i) => `-  ${i.path}: ${i.label}`)
            .join("\n")}`,
      )
      .join("\n");
    return { items: items(sel), diff, refused: [], confirmed: evidence() };
  };
  const apply = (sel: Selection): Fixed => {
    const shown = sel.confirmed?.files[0]?.version;
    if (!shown || shown !== `v${version}` || driftsLeft > 0) {
      if (shown && driftsLeft > 0) {
        driftsLeft -= 1;
        version += 1;
      }
      const text = shown
        ? `${USER} changed since the preview; nothing was written, review the changes and confirm again\n`
        : "refused: this request carries no preview to bind the write to, so nothing was written\n";
      return { code: 1, text, replan: plan({ ...sel, confirmed: null }) };
    }
    const picked = chosen(sel);
    return {
      code: 0,
      text: `${picked.length} ${picked.length === 1 ? "entry" : "entries"} removed from ${new Set(picked.map((i) => i.source)).size} file(s); backups kept in _backup/\n`,
    };
  };
  return { plan, apply };
}
