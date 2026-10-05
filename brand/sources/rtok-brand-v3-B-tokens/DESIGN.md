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

## Do's and Don'ts

- Do keep the bitset asymmetric (coral cells = Δ cuts, not decoration).
- Do keep Δ coral scarce in UI chrome too.
- Don't mix phosphor green from A or warm orange from v2.
- Don't symmetrize the grid into a bland LED pad — the cut pattern is the story.
