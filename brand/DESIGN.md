---
version: alpha
name: rtok
description: Hex Diff system with bitset-budget token mark + Δ. Blueprint cyan / coral delta / navy canvas. All mono.

colors:
  primary: "#5CE1FF"
  primary-muted: "#5CE1FF33"
  on-primary: "#06101A"
  delta: "#FF6B4A"
  canvas: "#06101A"
  surface-1: "#0A1622"
  surface-2: "#0F1E2E"
  hairline: "#1A3348"
  hairline-strong: "#2A4A66"
  ink: "#E8F7FF"
  ink-muted: "#8AA8BC"
  ink-subtle: "#5A7388"
  success: "#3DDC97"
  danger: "#FF6B4A"

typography:
  display:
    fontFamily: "IBM Plex Mono, JetBrains Mono, ui-monospace, monospace"
    fontSize: 28px
    fontWeight: 700
    lineHeight: 1.15
    letterSpacing: 0.04em
  title:
    fontFamily: "IBM Plex Mono, JetBrains Mono, ui-monospace, monospace"
    fontSize: 20px
    fontWeight: 700
    lineHeight: 1.25
  heading:
    fontFamily: "IBM Plex Mono, JetBrains Mono, ui-monospace, monospace"
    fontSize: 14px
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "IBM Plex Mono, JetBrains Mono, ui-monospace, monospace"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.55
  caption:
    fontFamily: "IBM Plex Mono, JetBrains Mono, ui-monospace, monospace"
    fontSize: 11px
    fontWeight: 500
    lineHeight: 1.35
  mono-metric:
    fontFamily: "IBM Plex Mono, JetBrains Mono, ui-monospace, monospace"
    fontSize: 18px
    fontWeight: 600
    lineHeight: 1.2
    fontFeature: "tnum"

rounded:
  sm: 4px
  md: 6px
  lg: 10px
  pill: 9999px

spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
  section: 48px

motion:
  state-change:
    duration: 180ms
    delay: 0ms
    timingFunction: cubic-bezier(0.4, 0, 0.2, 1)
  surface-enter:
    duration: 220ms
    delay: 0ms
    timingFunction: cubic-bezier(0.22, 1, 0.36, 1)
  surface-exit:
    duration: 160ms
    delay: 0ms
    timingFunction: cubic-bezier(0.3, 0, 1, 1)

components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.md}"
    padding: 10px
  delta-badge:
    textColor: "{colors.delta}"
  metric-card:
    backgroundColor: "{colors.surface-1}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: 16px
---

## Overview

**rtok B · Tokens** — keep Hex Diff system (cyan blueprint, coral Δ, navy, all IBM Plex Mono). Mark is **bitset budget**: a 4×4 token/bit grid — full cyan rows = kept context, dim = thinned, coral = measured cut/Δ. Reads as tokens at a glance without looking like hamburger bars. Wordmark stays tracked `RTOK` + `Δtok`.

## Colors

Same as Hex Diff: cyan primary, coral only for Δ, navy canvas, surface ladder + hairline.

## Typography

IBM Plex Mono everywhere. Tracked uppercase for brand `RTOK`.

## Shapes / Mark

Bitset mark is circular dots on a grid; UI chrome stays `rounded.md` 6px. Favicon = same bitset (reads at 16px).

## Motion & Micro-interaction

Motion is quick and understated. Nothing loops, and nothing moves only for decoration. The CLI and TUI do not animate; these tokens apply when the web UI or landing introduces motion.

Durations come from the rtok web admin that the Pyrlyn base layer inherited: `120ms` for hover color and border, `180ms` for selection, knobs and other on-screen state changes, with the standard UI curve `cubic-bezier(0.4, 0, 0.2, 1)`. The `state-change` token is that 180ms step. Surfaces entering the screen take longer and decelerate on the emphasized landing curve `cubic-bezier(0.22, 1, 0.36, 1)`. Surfaces leaving accelerate.

Honor `prefers-reduced-motion`: collapse every duration to `0.01ms` and do not start decorative animation. Animate only `transform` and `opacity` — never width, height, top, left, or other layout-forcing properties. Motion never carries information on its own.

## Voice & Content Strategy

- **Tone of voice:** Precise, calm, and utilitarian. Help, status and errors state facts and what to do next. No exclamation marks, marketing hyperbole, or conversational filler. The root CLI line is the model: "Token reduction for coding agents".
- **Capitalization:** Sentence case for CLI help, button labels, empty/loading/error copy, and screen subtitles ("Every hook, MCP and proxy call rtok handled"). Title case is reserved for nav group labels (Observe, Code, Setup) and screen titles (Overview, Worktrees, Doctor). The wordmark stays tracked uppercase `RTOK`. Command names stay lowercase (`rtok doctor`, `rtok web`).
- **Terminology:** In running text the product is `rtok`. Use the product's own words and do not swap them: *tokens* for counts, *Δ* / *Δtok* for measured savings (coral, scarce), *hook*, *session*, *agent*, *host* (the coding-agent app, not the machine), *plugin*, *worktree*, *junk*, *doctor*, *graph*, *task*, *skill*. The long-running supervisor command is `demon`, not "daemon". A *host* is not an *agent*; a *worktree* is not a generic checkout.
- **Button and action labels:** Verb phrases that name the outcome, as the UI already does: "Fix selected", "Clear safe junk", "Reconnect", "Go to Overview". Use "Cancel" to dismiss a pending action, not "OK" or "Submit".
- **Empty, loading, and error states:** Empty copy says what belongs in the list and, when there is a next step, the exact command or action: "No sessions yet. A session appears with its first hook." "No tasks yet. Add one with `rtok task create` and a title." "No task matches: clear the filter." Loading is a short status with an ellipsis ("Planning...", "loading the drill-down…", "Waiting for the first snapshot"). CLI errors are lowercase and name the failure ("invalid value 'x' for '--port <PORT>'", "some worktrees could not be removed"). Web errors state what failed and the next action: "rtok is offline" / "The link to rtok web dropped. It retries on its own; if the server restarted, open the URL it printed again."
- **Numbers, dates, and units:** Token counts use compact forms (`950`, `1.2k`, `12k`, `3.4M`); tables that need the exact integer use grouped digits (`1,234,567`). Percents are `12.5%`. Bytes use binary units with a space (`1023 B`, `1.0 KB`, `1.5 MB`). Durations in tables are tight (`3s`, `3m12s`, `1h05m`). Relative time in the web UI is `3s ago` / `2m ago` / `1h ago`; CLI session tables use the same pattern with the tight duration (`3m12s ago`); junk listings use `3 weeks ago (2026-09-09 14:02)` in local time. Hook timings keep one decimal and a space (`12.5 ms`). Missing values are `-` or `unknown`, never a fabricated zero.

## Do's and Don'ts

- Do keep the bitset asymmetric (coral cells = Δ cuts, not decoration).
- Do keep Δ coral scarce in UI chrome too.
- Don't mix phosphor green from A or warm orange from v2.
- Don't symmetrize the grid into a bland LED pad — the cut pattern is the story.
