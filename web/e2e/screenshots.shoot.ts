// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { type BrowserContext, expect, type Page, test } from "@playwright/test";
import { Rtok } from "./rtok";

// Regenerates web/screenshots/ against a real `rtok web` on a throwaway store:
// `npm run screenshots` (`just spa-screenshots`). The file is named `*.shoot.ts` so that the
// `spa-e2e` run never picks it up and rewrites tracked files.

const OUT = resolve(import.meta.dirname, "../screenshots");
const SCHEMES = ["dark", "light"] as const;
const PAGES = ["overview", "calls", "sessions", "plugins", "doctor", "logs"];
const WIDTHS = [1440, 768, 390] as const;
// The states are shot at the viewport of the widest and the narrowest layout, as a user sees them.
const STATE_VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
] as const;

/** Chromium encodes WebP from a canvas, so the shots need no image library. */
async function toWebp(context: BrowserContext, png: Buffer): Promise<Buffer> {
  const page = await context.newPage();
  try {
    const b64 = await page.evaluate(async (data) => {
      const bitmap = await createImageBitmap(
        await (await fetch(`data:image/png;base64,${data}`)).blob(),
      );
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
      const blob = await canvas.convertToBlob({ type: "image/webp", quality: 0.9 });
      const bytes = new Uint8Array(await blob.arrayBuffer());
      let bin = "";
      for (let i = 0; i < bytes.length; i += 0x8000)
        bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      return btoa(bin);
    }, png.toString("base64"));
    return Buffer.from(b64, "base64");
  } finally {
    await page.close();
  }
}

async function settle(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  // Charts animate in; reduced motion is emulated, this covers the last frame.
  await page.waitForTimeout(500);
}

async function shoot(page: Page, name: string, fullPage: boolean) {
  await settle(page);
  const png = await page.screenshot({ fullPage });
  writeFileSync(resolve(OUT, `${name}.webp`), await toWebp(page.context(), png));
}

async function withRtok(seeded: boolean, run: (rtok: Rtok) => Promise<void>) {
  const rtok = await Rtok.create(seeded);
  try {
    await rtok.start();
    await run(rtok);
  } finally {
    await rtok.dispose();
  }
}

test.beforeAll(() => mkdirSync(OUT, { recursive: true }));
test.use({ reducedMotion: "reduce" });

for (const scheme of SCHEMES) {
  for (const width of WIDTHS) {
    test(`pages ${width} ${scheme}`, async ({ browser }) => {
      test.setTimeout(120_000);
      const context = await browser.newContext({
        colorScheme: scheme,
        reducedMotion: "reduce",
        viewport: { width, height: 900 },
      });
      try {
        await withRtok(true, async (rtok) => {
          // A few more calls than the seed holds, so the tables and charts have rows.
          rtok.mcp("mem_search", { query: "fixture" }, 6);
          const page = await context.newPage();
          for (const id of PAGES) {
            await page.goto(`${rtok.url}/#/${id}`);
            await expect(page.getByRole("heading", { level: 1 })).toHaveText(id);
            await expect(page.getByRole("main").locator("[aria-busy]")).toHaveCount(0);
            await shoot(page, `${id}-${width}-${scheme}`, true);
          }
        });
      } finally {
        await context.close();
      }
    });
  }

  for (const viewport of STATE_VIEWPORTS) {
    const suffix = `${viewport.width}-${scheme}`;

    test(`calls-empty ${suffix}`, async ({ browser }) => {
      const context = await browser.newContext({
        colorScheme: scheme,
        reducedMotion: "reduce",
        viewport,
      });
      try {
        await withRtok(false, async (rtok) => {
          const page = await context.newPage();
          await page.goto(`${rtok.url}/#/calls`);
          await expect(page.getByText("No calls yet")).toBeVisible();
          await shoot(page, `calls-empty-${suffix}`, false);
        });
      } finally {
        await context.close();
      }
    });

    // The two states below need a server that never answers or answers with a failure, which the
    // real binary does not do on demand, so the page's socket is replaced by one that stays quiet
    // or adds the server's own failure frame (`snapshot_error`, web/src/api/ws.ts).
    test(`overview-loading ${suffix}`, async ({ browser }) => {
      const context = await browser.newContext({
        colorScheme: scheme,
        reducedMotion: "reduce",
        viewport,
      });
      try {
        await withRtok(true, async (rtok) => {
          const page = await context.newPage();
          await page.routeWebSocket(/\/ws$/, () => {});
          await page.goto(`${rtok.url}/#/overview`);
          await expect(page.getByRole("status").filter({ hasText: "Loading" })).toBeVisible();
          await shoot(page, `overview-loading-${suffix}`, false);
        });
      } finally {
        await context.close();
      }
    });

    test(`overview-error ${suffix}`, async ({ browser }) => {
      const context = await browser.newContext({
        colorScheme: scheme,
        reducedMotion: "reduce",
        viewport,
      });
      try {
        await withRtok(true, async (rtok) => {
          const page = await context.newPage();
          // The page keeps its last good snapshot and adds the error to it, so the real first
          // snapshot goes through and the failure frame follows; later ticks are dropped.
          await page.routeWebSocket(/\/ws$/, (ws) => {
            const server = ws.connectToServer();
            ws.onMessage((m) => server.send(m));
            let relayed = false;
            server.onMessage((m) => {
              if (relayed) return;
              relayed = true;
              ws.send(m);
              ws.send(
                JSON.stringify({
                  type: "snapshot",
                  error: "the ledger is locked by another rtok process",
                }),
              );
            });
          });
          await page.goto(`${rtok.url}/#/overview`);
          await expect(page.getByText("Something went wrong")).toBeVisible();
          await shoot(page, `overview-error-${suffix}`, false);
        });
      } finally {
        await context.close();
      }
    });
  }
}
