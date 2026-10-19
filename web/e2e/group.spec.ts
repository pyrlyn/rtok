// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, test } from "@playwright/test";
import { spawnGroup, stopGroup } from "./group";

// The leader stands in for `rtok web`, the writer for the doctor probe it leaves behind: the
// writer keeps recreating a file in the directory until it is stopped. Bounded, so a writer a
// broken stop leaks still ends on its own.
const LEADER = '/bin/sh -c "$2" writer "$1" & wait';
const WRITER =
  'echo $$ > "$1/writer.pid"; i=0; while [ $i -lt 1500 ]; do : > "$1/late"; i=$((i+1)); sleep 0.01; done';

const alive = (pid: number) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

async function writerOf(dir: string) {
  const file = join(dir, "writer.pid");
  await expect.poll(() => existsSync(file) && readFileSync(file, "utf8").trim() !== "").toBe(true);
  return Number(readFileSync(file, "utf8"));
}

for (const [name, writer, graceMs] of [
  ["stopping a group waits for the writer its leader left behind", WRITER, 5000],
  [
    "a writer that ignores SIGTERM is killed after the grace period",
    `trap "" TERM; ${WRITER}`,
    300,
  ],
] as const) {
  test(name, async () => {
    const dir = mkdtempSync(join(tmpdir(), "rtok-group-"));
    let pid: number | undefined;
    try {
      const leader = spawnGroup("/bin/sh", ["-c", LEADER, "leader", dir, writer], {
        stdio: "ignore",
      });
      pid = await writerOf(dir);
      await stopGroup(leader, graceMs);
      expect(alive(pid)).toBe(false);
      rmSync(dir, { recursive: true, force: true });
      await new Promise((r) => setTimeout(r, 100));
      expect(existsSync(dir)).toBe(false);
    } finally {
      if (pid !== undefined && alive(pid)) process.kill(pid, "SIGKILL");
      rmSync(dir, { recursive: true, force: true });
    }
  });
}
