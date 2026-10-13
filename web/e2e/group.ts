// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { type ChildProcess, type SpawnOptions, spawn } from "node:child_process";

const POLL_MS = 20;

/** Groups this worker started and has not seen empty yet. */
const live = new Set<number>();

// A worker that exits with a server still up (a crash, an interrupted run) must not leave the
// group behind writing into a home nobody removes.
process.on("exit", () => {
  for (const pgid of live) {
    try {
      process.kill(-pgid, "SIGKILL");
    } catch {
      // Already empty.
    }
  }
});

/**
 * Spawns `bin` as the leader of a new process group. Whatever it starts joins that group (unless
 * it starts a session of its own), so `stopGroup` reaches the children as well as the leader.
 */
export function spawnGroup(bin: string, args: string[], options: SpawnOptions): ChildProcess {
  const child = spawn(bin, args, { ...options, detached: true });
  if (child.pid !== undefined) live.add(child.pid);
  return child;
}

/** Signals every process of the group; false once none is left. */
function signal(pgid: number, sig: NodeJS.Signals | 0): boolean {
  try {
    process.kill(-pgid, sig);
    return true;
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === "ESRCH") return false;
    throw e;
  }
}

/**
 * SIGTERM to the whole group, SIGKILL after `graceMs`, and resolves once no process of it is
 * left. Waiting for the leader alone is not enough: `rtok web` stopped mid-snapshot leaves its
 * doctor probe (`rtok mcp`) running, and the probe creates `rtok.db-wal` in the home the caller
 * is about to remove (T642). A pgid is not reused while any member lives, so polling it is safe
 * until the first `ESRCH`, after which the group is never signalled again.
 */
export async function stopGroup(child: ChildProcess, graceMs = 5000): Promise<void> {
  const pgid = child.pid;
  if (pgid === undefined || !live.has(pgid)) return;
  const deadline = Date.now() + graceMs;
  let alive = signal(pgid, "SIGTERM");
  let killed = false;
  while (alive) {
    if (!killed && Date.now() >= deadline) {
      killed = true;
      signal(pgid, "SIGKILL");
    }
    // Yielding also lets Node reap the leader, whose zombie would keep the group non-empty.
    await new Promise((r) => setTimeout(r, POLL_MS));
    alive = signal(pgid, 0);
  }
  live.delete(pgid);
}
