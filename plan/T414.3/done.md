Chip, DataTable, Kpi, Marks, Panel, Pill, Search, Sparkline, Switch: brand roles only (no hex literals), stories updated.

Check: `just spa-stories` (axe) green; a grep over `web/src` finds no hex colour literal outside tests and fixtures.

Plan (approved mockup: the T414.2 shell and Overview, built on the Pyrlyn base component specs in `brand/node_modules/@pyrlyn/brand/base/components/*.md`):
1. Chip: `radius-md`, pressed border `accent`/50%, 44px minimum below 768px, colours over `duration-fast`.
2. Pill: `fail` on the `danger` roles, tinted backgrounds at 10%.
3. Switch: on state fills track and border with `accent`, knob `on-accent`; knob over `duration-base` with `ease-emphasized`; 44px hit area below 768px (not only on coarse pointers).
4. Search: `bg`/60% field, `size-control`, 44px and `text-sm` below 768px, hover and transition.
5. DataTable: row border at 60%, selected row `accent`/10%, hover over `duration-fast`.
6. Panel and Kpi: `duration-fast` hover; a linked Kpi gets `border-strong` on hover.
7. Marks: `BudgetGrid` cells on the `accent` and `delta` roles instead of the `--rtok-brand-*` fills (same values); Sparkline already draws on the `accent` role, so it only gets its story.
8. No hex literal left in `web/src` outside tests: `theme.ts` reads `--pyr-bg`, `stage3d.ts` reads the `--pyr-fg-subtle` role.
9. Stories for the states the base specs name, then `just js`, `spa-test`, `spa-stories`, `spa-e2e`.

Result (2026-10-08, Claude Code / claude-sonnet-5-5): the components follow the Pyrlyn base component specs the T414.2 mockup is built on. `Chip`: `radius-md`, pressed border `accent`/50%, `size-control-sm`, 44px below 768px, colours over `duration-fast`. `Pill`: `fail` on the `danger` roles, washes at 10%. `Switch`: on track and border solid `accent`, knob and spinner `on-accent`, knob over `duration-base` with `ease-emphasized`, 44px hit area below 768px instead of only on coarse pointers. `Search`: `size-control`, `bg`/60%, 44px and `text-sm` below 768px. `DataTable`: row rule at 60%, selected row `accent`/10%, hover over `duration-fast`. `Kpi`: a linked card takes `border-strong` on hover. `Panel`: header rule at 60%. `BudgetGrid` cells use the `accent` and `delta` roles instead of `--rtok-brand-*` (same values). `Sparkline` already drew on the `accent` role and needed no change. `theme.ts` reads the `theme-color` from `--pyr-bg` and `stage3d.ts` reads `--pyr-fg-subtle`, so `web/src` holds no hex literal outside tests. New stories: `Chip` OnLight, `Switch` OnLight and PendingOnLight, `DataTable` SelectedRow and SelectedRowLight, `BudgetGrid` Budget and BudgetLight. `just js`, `spa-typecheck`, `spa-test`, `spa-stories` (168) and `spa-e2e` green.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
