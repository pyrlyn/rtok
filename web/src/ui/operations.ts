// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

export type Kind = "success" | "info" | "warn" | "error";

// The verbs of the CLI table `OPERATION_ICONS` in `src/ui/style.rs` (T436), same stems in the
// same order; `operations.test.ts` fails when the two lists drift. A stem is matched as a
// substring of the verb, so `uninstall` has to stay above `install`, which it contains.
// The value is the name of a brand icon under `brand/icons/ui/`.
export const OPERATION_ICONS: readonly (readonly [stem: string, icon: string])[] = [
  ["uninstall", "remove"],
  ["remov", "remove"],
  ["prun", "remove"],
  ["install", "install"],
  ["upgrad", "update"],
  ["updat", "update"],
  ["download", "fetch"],
  ["fetch", "fetch"],
  ["link", "link"],
  ["roll", "rollback"],
  ["search", "search"],
  ["doctor", "doctor"],
  ["index", "index"],
  ["worktree", "worktrees"],
  ["compress", "compress"],
  ["expand", "expand"],
  ["bench", "bench"],
  ["start", "start"],
  ["stop", "stop"],
];

const KIND_ICONS: Record<Kind, string> = {
  success: "success",
  info: "info",
  warn: "warning",
  error: "error",
};

/** The icon of the operation `verb` names, else the one of `kind`. */
export function operationIcon(verb: string, kind: Kind = "info"): string {
  const v = verb.toLowerCase();
  return OPERATION_ICONS.find(([stem]) => v.includes(stem))?.[1] ?? KIND_ICONS[kind];
}
