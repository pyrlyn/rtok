# Bitset B — responsive shell

Layout/density only. Brand colors, F icons, and IBM Plex Mono stay fixed.

## Shared breakpoints

| Token | Range | Slint `RtokTheme` |
|-------|-------|-------------------|
| `sm` / mobile | &lt; 640px | `bp-sm` |
| `md` / tablet | 640–1023px | `bp-md` |
| `lg` / desktop | 1024–1439px | (≥ `bp-md`) |
| `xl` / wide | 1440–1919px | `bp-lg` |
| `2xl` / ultrawide+TV | ≥ 1920px (to ~3840) | `bp-xl` |

## Slint webui (`theme.slint` + `app.slint`)

Derived from tracked `win-w` / `win-h` (updated via `changed width`/`height` + `init` to avoid Slint layoutinfo binding loops):

| Width | Nav | Content |
|-------|-----|---------|
| &lt; 640 (`compact`) | Top horizontal icon bar (icons only, equal buttons, ≥36px hits) | Full width; plugins/calls **stacked** (list then detail) |
| 640–1023 (`rail`) | 56px rail, icons only | Side-by-side panes |
| ≥ 1024 (`full-nav`) | 200px sidebar with labels + status | Side-by-side |
| ≥ 1440 (`wide`) | Full sidebar | More padding; plugin list ~240–280; calls list `min(480, 42% width)` |
| ≥ 1920 (`ultrawide`) | Full sidebar | `space-5` padding; title ~26px; body +1px; content `max-width` ~1600px |

- Window: `min-width: 320px`, `min-height: 480px`; preferred ~960×640.
- `TokenStatsWidget`: wraps to 2×3 when strip width &lt; 760px.
- Exported structs and MainWindow in/in-out property names unchanged for Rust bindings.

## Verify

```bash
slint-viewer --check ui/app.slint
```

(from `crates/rtok-webui`)
