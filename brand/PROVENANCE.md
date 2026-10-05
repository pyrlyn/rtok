# rtok brand: provenance

Moved from the pyrlyn/brand v0.3.0 README (sections "Conflicts", "Adopting in each surface" and
"Sources"), with paths adjusted to this folder. These notes record how the locked rtok values were
chosen; they were written against rtok `559a7a11` and the `design/web-admin` branch. Since then the web
admin moved to React/Vite on `main` and ships `web/src/styles/tokens.css`, which equals
`dist/legacy/tokens.css`, so the web-admin steps below are history.

## Conflicts

rtok surfaces compared (read-only): web admin `web/` on branch `design/web-admin` (draft PR #447,
not live), rtok docs site `site/` (deployed to listepo.github.io/rtok), the rtok page on the
listepo project site (listepo/landing `main`, deployed), the TUI `src/tui/theme.rs`, and the v3-B
brand pack. Rule: prefer what is live in production; where a live value is not actually rendered,
or fails WCAG AA, take the AA-passing value and say so. Paths are relative to each repo;
`landing:` = listepo/landing at `b4a06f1`, `web/` = rtok `design/web-admin` at `1e144253`,
others = rtok `origin/main` at `559a7a11`.

| Token | Values per surface (file:line) | Canonical | Reason |
|---|---|---|---|
| `accent-fg` (cyan as text on light) | web `web/src/input.css:18` `#006F8C` · landing `src/lib/site.ts:56` + rtok `docs/site.md:15` accentLight `#0B7FA0` · landing `src/styles/tokens.css:488` `#00708C` | `#006F8C` | The landing's `--accent-light` is set (`global.css:18`, `Base.astro:21`) but no rule reads it and the site is dark-only, so `#0B7FA0` is not rendered; it is also 4.32:1 on light `bg` (fails AA). `tokens.css` is not imported anywhere (dead). `#006F8C` is 5.38:1. |
| `fg-muted` light | web `input.css:17` `#4F6679` · v3 `TOKENS.md` `#5A7388` | `#4F6679` | `#5A7388` is 4.24:1 on `surface-2` (fails AA); `#4F6679` ≥ 5.1:1 everywhere. |
| `fg-subtle` dark | web `input.css:29` `#7F9AAE` · v3 `DESIGN.md` `#5A7388` | `#7F9AAE` | `#5A7388` is 3.87:1 on dark `bg`. |
| `success` light | web `input.css:20` fill `#3DDC97` + text `#0B7A50` · v3 `TOKENS.md` `#0F8F5F` (one color for fill and text) | fill `#3DDC97`, `success-fg` `#0B7A50` | `#0F8F5F` as text is 3.85:1 on light `bg`; split fill/text like accent and delta. |
| `delta-fg` / `danger-fg` light | web `input.css:19` `#B8361C` · landing `tokens.css:488` `#B93A1D` (dead) · v3 `TOKENS.md` `#FF6B4A` text, `#E85A3C` for mark cuts on light | fill `#FF6B4A`, text `#B8361C` | Coral text on light is 2.64:1. The mark keeps `#FF6B4A` on its navy tile. |
| `accent-muted` (selected bg) | web `input.css:69,82` `accent/15` (pressed chip, current nav), `input.css:94` `accent/10` (selected row) · v3 `DESIGN.md` `#5CE1FF33`, `TOKENS.md` `#5CE1FF29` light | `#5CE1FF26` (15%) both themes; selected table rows `accent` at 10% | The web admin is the only surface that implements selection; the v3 values are mock-ups. |
| `on-accent` | web `input.css:18`, v3: `#06101A` · landing `global.css:26` `#050810` (site shell default, the rtok theme does not override it) · landing `tokens.css:484` `#041018` (dead) | `#06101A` | Brand navy; the landing difference is invisible (both ≈ black) and comes from the listepo shell. |
| `focus` ring | web `tailwind.config.js:49` 2px `canvas` + 2px cyan (both themes) · landing `global.css:58` 3px + 5px accent · site: Hextra default | 2px `bg` + 2px `focus`; `focus` = cyan dark / `#006F8C` light | Cyan ring on light is 1.44:1 (WCAG 1.4.11 needs 3:1). |
| Heading size | web `index.html:64` page title `text-base` 14px bold, `app.js:327` panel title `text-xs` 12px/600 · v3 `DESIGN.md` heading 14px/600 | 14px/600 (`text-base`) | Web page title and the v3 pack agree on 14px. |
| Caption weight | web `tailwind.config.js:33` 11px, regular · v3 `DESIGN.md` caption 11px/500 | 11px/400 | The web admin ships only 400/600/700 woff2 files. |
| Radius | web `tailwind.config.js:43` 4/6/10/14 · site `custom.css:25` 12px hero, `:41` 8px icon tile, `:117` 8px, `:131` 10px · landing `global.css:73` 6/10/14/20/28 (listepo shell) | 4/6/10/14/full | Only rtok-specific scale; site values are one-off marketing frames (map 12→`xl`/`lg`, 8→`md`/`lg` on adoption); landing radii belong to the listepo shell, not rtok. |
| Breakpoint names | site `custom.css:11-14`: `--rtok-bp-sm` 640, `-md` 1024, `-lg` 1440, `-xl` 1920 · web `tailwind.config.js:54`: xs 360, sm 640, md 768, lg 1024, xl 1440, 2xl 1920 | Tailwind names and values | Same pixel steps; Tailwind adds 360/768. Mapping: site `bp-sm`=`sm`, `bp-md`=`lg`, `bp-lg`=`xl`, `bp-xl`=`2xl`. |
| Easing | web: Tailwind default `cubic-bezier(0.4,0,0.2,1)` · landing `global.css:76` `cubic-bezier(.22,1,.36,1)` (live on the rtok page) | both, as `standard` and `emphasized` | Different jobs: UI state vs entrances. |
| Elevation | web `tailwind.config.js:46-48` e1–e3 · site `custom.css:27` `0 24px 48px rgba(0,0,0,.45)` hero, `:118` mobile · landing `global.css` `--shadow-1..3` (listepo shell) | web e1–e3 | Only rtok-specific scale; the hero shadow is close to `e3`. |
| Font | web `web/assets/fonts/*.woff2`, wordmark SVG, v3 pack: IBM Plex Mono · docs site: Hextra default fonts (no override in `custom.css`) · landing `global.css:61-62` Inter + system mono (rtok page included) | IBM Plex Mono | The brand pack and the product UI use it; the two sites never loaded it. |
| Hextra primary | site `custom.css:3-5` `hsl(190 100% 68%)` ≈ `#5CE4FF` | `#5CE1FF` = `hsl(191 100% 68%)` | 1° hue drift; set `--primary-hue: 191deg` when adopting. |
| Mark on light | site `static/logo.svg` and web `assets/logo.svg`: identical, navy `#06101A` tile · v3 `webui-mock/themes/logo-light.svg`: ink `#0B1A24` tile, coral `#E85A3C`, dim 0.4 (not used anywhere) · v3 `TOKENS.md` `mark-tile` light `#0B1A24` | one mark, `logo/rtok-mark.svg`, both themes; `mark-tile` token keeps `#0B1A24` light | The live SVG is identical on every surface; the light variant stayed a mock. |
| Wordmark | site `static/logo-wordmark.svg` = `logo-wordmark-dark.svg` (byte-identical) · PNG `logo-wordmark-2x.png` = `logo-wordmark-dark-2x.png` | one `logo/rtok-wordmark.svg` | Duplicates, not variants. |
| Border naming | web `line`/`line-strong` · v3 `hairline`/`hairline-strong` · site `#1A3348` literals | `border`/`border-strong` | Values agree; names unified. |
| Surface ladder | v3 `surface-1`, `surface-2` · web adds `surface-3`, `warn`, `shine`, glass alpha | web ladder | No conflict; the v3 pack lacks these. |
| TUI palette | `src/tui/theme.rs:10-18` ANSI Cyan / DarkGray / Green / Yellow / Red | unchanged | Terminal palette colors, not hex; they map to accent / fg-subtle / success / warn / danger. |

No other divergent values: the dark palette (`#06101A`, `#0A1622`, `#0F1E2E`, `#1A3348`,
`#2A4A66`, `#E8F7FF`, `#8AA8BC`), cyan, coral, spacing, durations (120/180ms) and the 9 UI icons
are identical across the web admin, docs site, landing and the v3 pack wherever they appear.

## Adopting in each surface

Nothing was rewired in this change. The rtok repo still carries its own runtime copies; these are
the steps to switch each surface to this folder.

**Web admin** (`rtok/web/` on `design/web-admin`, currently Tailwind v3 standalone CLI)
1. Move the web admin to Tailwind v4: delete `web/tailwind.config.js` (its colors, type scale,
   radii, shadows, screens and durations all come from `dist/legacy/tailwind-v4.css`) and build with
   `@tailwindcss/cli` instead of the v3 CLI.
2. `web/src/input.css`: replace `@tailwind base/components/utilities` with `@import "tailwindcss";`
   and `@import "<brand>/dist/legacy/tailwind-v4.css";`, add `@source "../index.html"; @source "../app.js";`,
   replace the `@font-face` lines 7-9 with `@import "@pyrlyn/brand/base/fonts.css";` (keep fonts at
   `web/assets/fonts/` or next to the output) and delete the `:root` / `.dark` variable blocks
   (lines 13-35); set `data-theme="dark"` (or keep `.dark`, which `tokens.css` also honors) on `<html>`.
3. Rename utilities: `canvas`/`navy` → `bg`, `line` → `border`, `ink` → `fg`, `text-accent-on` stays,
   `delta`/`success`/`warn` stay; `rounded-*`, `shadow-e*`, `shadow-ring`, `duration-fast` stay;
   `duration-base` becomes the token's 180ms (same as today). Either keep the `@layer components`
   classes (in v4 write them as `@utility` or plain CSS) or use `dist/legacy/components.css`.
4. `web/assets/fonts/*.woff2` and `web/assets/icons|logo.svg` are identical to `@pyrlyn/brand/base/fonts/`,
   `@pyrlyn/brand/base/icons/ui/`, `logo/rtok-mark.svg`.
5. Rebuild `web/tailwind.css` with
   `npx @tailwindcss/cli -i src/input.css -o tailwind.css --minify` (update `web/README.md`) and
   re-shoot `web/screenshots/`.

**rtok docs site** (`rtok/site/`, Hugo + Hextra)
1. Load `brand/dist/legacy/tokens.css` through Hugo Pipes from
   `layouts/_partials/custom/head-end.html` (Hextra ships its own CSS, so no Tailwind build is needed;
   use the Tailwind v4 CLI setup above only for new Tailwind-based pages).
2. `site/assets/css/custom.css`: delete the `--rtok-canvas/accent/delta/ink` block (lines 6-9) and
   use `var(--rtok-bg)`, `var(--rtok-accent)`, `var(--rtok-delta)`, `var(--rtok-fg)`; replace the
   literals `#1A3348` (lines 26, 43), `#0A1622` (42), `#5CE1FF` (47, 78, 81) and
   `var(--rtok-canvas, #06101A)` (187) with `--rtok-border`, `--rtok-surface`, `--rtok-accent`,
   `--rtok-bg`; hero shadow (27) → `var(--rtok-shadow-e3)`; set `--primary-hue: 191deg`.
3. Keep `--rtok-bp-*` names or switch to the Tailwind names (values are the same).
4. Optional: load `@pyrlyn/brand/base/fonts.css` and set Plex Mono for headings.
5. `site/static/{logo,logo-dark,favicon,logo-wordmark*}.svg`, `site/static/icons/*` and
   `site/data/icons.yaml` must stay in `site/` (Hugo serves them); refresh them from `logo/` and
   `icons/feature/`. `just site` must pass (`--panicOnWarning`).

**rtok page on the listepo project site** (listepo/landing; not touched in this change)
1. `docs/site.md:15` in rtok (synced to `content/projects/rtok.md` by `sync-docs.yml`): set
   `accentLight: "#006F8C"`. The landing's `src/lib/site.ts:56` fallback can get the same value.
2. If the landing adds a light theme or starts consuming `--accent-light`, use `accent-fg`.
3. Optionally add `onAccent` for the rtok theme (`#06101A`) and use Plex Mono in the rtok hero.
4. `src/styles/tokens.css` is not imported anywhere; its rtok values (`#00708C`, `#B93A1D`,
   `#041018`) are stale.
5. `public/images/rtok/*` are the landing's own renders; `public/favicon.svg` is the listepo logo,
   not rtok's.

**TUI** (`rtok/src/tui/theme.rs`): no change; ANSI colors already follow the roles.

## Sources

The provenance snapshots are not kept in this repo. They stay in pyrlyn/brand at tag v0.3.0, under
[`brands/rtok/sources/`](https://github.com/pyrlyn/brand/tree/v0.3.0/brands/rtok/sources) (verbatim copies, sha256-verified at import, of everything the
tokens came from) and [`brands/rtok/screenshots/`](https://github.com/pyrlyn/brand/tree/v0.3.0/brands/rtok/screenshots) (web admin responsive shots and the
brand demo). Paths below are relative to `brands/rtok/sources/` there:

- `rtok/<path>`: files from `listepo/rtok` `origin/main` at `559a7a11`: docs-site CSS, icons.yaml and `site/static` brand assets;
  `docs/assets/landing-retina/*` (the 19 retina PNG exports that used to live in rtok);
  `docs/site.md`; `src/tui/theme.rs`.
- `rtok-branch-design-web-admin/web/`: `tailwind.config.js`, `src/input.css`, `assets/`
  and `screenshots/` from branch `design/web-admin` at `1e144253` (draft PR #447, not on main).
- `rtok-brand-v3-B-tokens/`: the v3-B brand pack (`DESIGN.md`, `BRIEF.md`,
  `webui-mock/themes/TOKENS.md`, alternative marks and icon sets, previews).
  19 files byte-identical to files already here were left out; see `DUPLICATES.md` there.

These are provenance snapshots, not presets. Only `tokens.json` (on top of `@pyrlyn/brand/base/tokens.json`) and `dist/` are
authoritative.
