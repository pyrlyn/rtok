# rtok Bitset B — light & dark

Accent and Δ stay brand-constant. Surfaces/ink flip.

| Token | Dark | Light |
|---|---|---|
| `canvas` | `#06101A` | `#F4F8FB` |
| `surface-1` | `#0A1622` | `#FFFFFF` |
| `surface-2` | `#0F1E2E` | `#E8EEF4` |
| `hairline` | `#1A3348` | `#C5D3E0` |
| `ink` | `#E8F7FF` | `#0B1A24` |
| `ink-muted` | `#8AA8BC` | `#5A7388` |
| `accent` | `#5CE1FF` | `#5CE1FF` |
| `accent-muted` | `#5CE1FF33` | `#5CE1FF29` |
| `delta` | `#FF6B4A` | `#FF6B4A` (text); cuts on mark tile may use `#E85A3C` for AA on light paper if needed |
| `success` | `#3DDC97` | `#0F8F5F` |
| `mark-tile` | `#06101A` | `#0B1A24` (bitset always on dark tile) |

## Rules

- Toggle: `RtokTheme.dark`
- Bitset mark **always** sits on a dark tile in both themes (cyan/coral need the navy ground).
- Primary CTA: accent fill + `on-accent` (#06101A) in both modes.
- Δ badges: coral text; on light mode prefer outline/hairline chip, not coral fill (contrast).
- Active nav: `accent-muted` bg + `accent` fg — works on both.
- Default product theme: **dark**. Light is first-class, not an afterthought invert.
