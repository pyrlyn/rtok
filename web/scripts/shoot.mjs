// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Re-shoot `web/screenshots/` from the built SPA (`npm run build` first). Playwright + Chromium
// (the same tooling the original set used), `?sample` data, dark and light via
// `localStorage['rtok-theme']`, DPR 1, reduced motion so charts and the orb are still. Named
// `<page>-<width>-<theme>.webp` to match the set from the T414 restyle; the empty, loading and
// error states come from `?sample=<state>` (T414.7). Chromium encodes webp (`toWebp`).
// `npm run screenshots` (`just spa-screenshots`).
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const dist = fileURLToPath(new URL("../dist", import.meta.url));
const out = fileURLToPath(new URL("../screenshots", import.meta.url));
const PAGES = ["overview", "plugins", "calls", "sessions", "doctor", "logs"];
const WIDTHS = [390, 768, 1440];
const THEMES = ["dark", "light"];
const HEIGHTS = { 390: 844, 768: 1024, 1440: 900 };
// State shots, at the widths the original set had them.
const STATES = [
    { name: "calls-empty", id: "calls", sample: "empty" },
    { name: "overview-loading", id: "overview", sample: "loading" },
    { name: "overview-error", id: "overview", sample: "error" },
];
const STATE_WIDTHS = [390, 1440];

const MIME = {
    ".html": "text/html",
    ".js": "text/javascript",
    ".css": "text/css",
    ".svg": "image/svg+xml",
    ".webp": "image/webp",
    ".png": "image/png",
    ".woff2": "font/woff2",
    ".json": "application/json",
};

const serve = () =>
    new Promise((resolve) => {
        const server = createServer(async (req, res) => {
            const path = normalize(join(dist, (req.url ?? "/").split("?")[0].replace(/^\/+/, "")));
            if (!path.startsWith(dist)) {
                res.writeHead(403).end();
                return;
            }
            try {
                const body = await readFile(path);
                res.writeHead(200, {
                    "content-type": MIME[extname(path)] ?? "application/octet-stream",
                });
                res.end(body);
            } catch {
                const body = await readFile(join(dist, "index.html"));
                res.writeHead(200, { "content-type": "text/html" });
                res.end(body);
            }
        });
        server.listen(0, "127.0.0.1", () => resolve(server));
    });

const toWebp = async (page, png) => {
    // Chromium can encode webp; sips and Playwright's screenshot cannot.
    const data = await page.evaluate(async (bytes) => {
        const img = new Image();
        img.src = `data:image/png;base64,${bytes}`;
        await img.decode();
        const c = document.createElement("canvas");
        c.width = img.naturalWidth;
        c.height = img.naturalHeight;
        c.getContext("2d").drawImage(img, 0, 0);
        return c.toDataURL("image/webp", 0.9).split(",")[1];
    }, png.toString("base64"));
    return Buffer.from(data, "base64");
};

const main = async () => {
    await mkdir(out, { recursive: true });
    const server = await serve();
    const { port } = server.address();
    const browser = await chromium.launch();
    let n = 0;
    try {
        for (const theme of THEMES) {
            for (const width of WIDTHS) {
                const context = await browser.newContext({
                    viewport: { width, height: HEIGHTS[width] },
                    deviceScaleFactor: 1,
                    colorScheme: theme === "dark" ? "dark" : "light",
                    reducedMotion: "reduce",
                });
                await context.addInitScript((t) => {
                    try {
                        localStorage.setItem("rtok-theme", t);
                    } catch {}
                }, theme);
                const page = await context.newPage();
                const shots = [
                    ...PAGES.map((id) => ({ name: id, id, sample: "" })),
                    ...(STATE_WIDTHS.includes(width) ? STATES : []),
                ];
                for (const { name, id, sample } of shots) {
                    const query = sample ? `sample=${sample}` : "sample";
                    await page.goto(`http://127.0.0.1:${port}/?${query}#/${id}`, {
                        waitUntil: "networkidle",
                    });
                    await page.waitForTimeout(250);
                    const png = await page.screenshot({ fullPage: true, type: "png" });
                    const webp = await toWebp(page, png);
                    await writeFile(join(out, `${name}-${width}-${theme}.webp`), webp);
                    n += 1;
                }
                await context.close();
            }
        }
    } finally {
        await browser.close();
        server.close();
    }
    console.log(`${n} shots → ${out}`);
};

await main();
