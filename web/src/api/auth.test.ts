// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test, vi } from "vitest";
import { authProblem } from "./auth";

const answer = (status: number) => vi.fn(async () => new Response(null, { status }));

describe("why /ws refused (T944)", () => {
  test("no token is missing, without a request", async () => {
    const probe = answer(204);
    expect(await authProblem(null, probe)).toBe("missing");
    expect(await authProblem("", probe)).toBe("missing");
    expect(probe).not.toHaveBeenCalled();
  });

  test("a token the server refuses is rejected", async () => {
    expect(await authProblem("stale", answer(401))).toBe("rejected");
  });

  test("an accepted token or a dead server is a plain disconnect", async () => {
    expect(await authProblem("good", answer(204))).toBeNull();
    expect(await authProblem("good", answer(500))).toBeNull();
    const down = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    });
    expect(await authProblem("good", down)).toBeNull();
  });

  test("the probe sends the token as a bearer header and skips the cache", async () => {
    const probe = answer(204);
    await authProblem("abc", probe);
    expect(probe).toHaveBeenCalledWith("/auth", {
      headers: { Authorization: "Bearer abc" },
      cache: "no-store",
    });
  });
});
