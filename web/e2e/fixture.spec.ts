// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { spawnSync } from "node:child_process";
import { expect, test } from "@playwright/test";
import { failure, Rtok } from "./rtok";

// T643: on a Mac the per-user temp dir collects tens of thousands of entries, and under this
// suite's load realpath(3) on a home there stalled for seconds, so seed calls hit their timeout.
test("the throwaway home lives under /tmp, not the per-user temp dir", async () => {
  test.skip(process.platform === "win32", "Windows has no /tmp; it keeps os.tmpdir()");
  const rtok = await Rtok.create();
  try {
    expect(rtok.home).toMatch(/^\/tmp\/rtok-spa-e2e-/);
  } finally {
    await rtok.dispose();
  }
});

// T643: the stalled seed used to read `exited null:`, which looked like a crash.
test("a call killed by its timeout says it timed out", () => {
  const out = spawnSync(process.execPath, ["-e", "setTimeout(() => {}, 10_000)"], {
    timeout: 200,
    encoding: "utf8",
  });
  expect(failure("rtok mcp", out, 200)).toBe("rtok mcp timed out after 0.2 s: ");
  const ok = spawnSync(process.execPath, ["-e", ""], { encoding: "utf8" });
  expect(failure("rtok mcp", ok, 200)).toBeUndefined();
});
