// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { defineConfig, devices } from "@playwright/test";

// Not part of `playwright.config.ts`: the shots rewrite files in the repository, so `spa-e2e`
// must never run them. Run with `npm run screenshots` (`just spa-screenshots`).
const channel = process.env["SPA_BROWSER_CHANNEL"] || undefined;

export default defineConfig({
  testDir: "e2e",
  testMatch: "*.shoot.ts",
  fullyParallel: true,
  // Each test starts its own `rtok web`; three at once on a busy host missed its health deadline.
  workers: 2,
  expect: { timeout: 15_000 },
  retries: 0,
  reporter: "list",
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], channel } }],
});
