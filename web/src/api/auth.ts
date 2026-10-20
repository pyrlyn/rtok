// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Why `/ws` refused (T944). A browser WebSocket reports a 401 as a bare close, the same as a
// server that is down, so the offline screen asks `/auth`, which runs the same checks over HTTP.

/** `missing`: the page never got `?token=`; `rejected`: the server (restarted) refuses it. */
export type AuthProblem = "missing" | "rejected";

export type Probe = (url: string, init: RequestInit) => Promise<Response>;

/** `null` when the token is not the problem: accepted, or the server cannot be reached at all. */
export async function authProblem(
  token: string | null,
  probe: Probe = (url, init) => globalThis.fetch(url, init),
): Promise<AuthProblem | null> {
  if (!token) return "missing";
  try {
    const res = await probe("/auth", {
      headers: { Authorization: `Bearer ${token}` },
      cache: "no-store",
    });
    return res.status === 401 ? "rejected" : null;
  } catch {
    return null;
  }
}
