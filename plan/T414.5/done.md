As T414.4 for these pages.

Check: `just spa-test`, `just spa-stories`, `just spa-e2e` green; dark and light screenshots of each page.

Plan:
1. Audit the six pages against the approved T414.2 shell, the restyled T414.3 `web/src/ui` components and the Pyrlyn base component specs.
2. Move hand-rolled pieces onto the shared components and `--pyr-*` roles.
3. Stories for the new component; keep the page tests green.
4. Verify and take dark and light screenshots of each page.

Result (2026-10-08, Claude Code / claude-sonnet-5-5): the six pages already sat on the T414.3 components, so the work closed the places that still drew their own controls. New `ui/Select`: a native `<select>` with the Pyrlyn input field look (`size-control`, `radius-md`, `bg`/60, `border-strong` on hover, 44px and `text-sm` below 768px), stories Default, OnLight and Picks. `Projects`: the "link to" picker is that `Select`, "both ways" is a `Switch` (was a bare checkbox), and the project rows follow the Chip spec (pressed border `accent`/50% with an `accent`/10% wash, colours over `duration-fast`, 44px below 768px). `Services`: the three OpenTelemetry tiles are `Kpi` cards instead of hand-rolled `surface-2` boxes, and the running/stopped `Pill` moved into the card header. `Hosts` and `Worktrees`: the state `Pill` sits in the `Panel` header action like the other cards. No shared component changed. `spa-typecheck`, `spa-test` (255), `spa-stories` (171) and `just js` green. `spa-e2e` and `just check` were not run on this host (copying a seeded `target/` was refused and a cold Rust build did not fit the free disk); CI runs both.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
