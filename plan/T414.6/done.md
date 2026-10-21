As T414.4; the Graph 3D view keeps its renderer and takes its colours from the roles.

Check: `just spa-test`, `just spa-stories`, `just spa-e2e` green; dark and light screenshots of each page and state.

Plan (shared components stay as T414.3 left them; T414.4 and T414.5 own the other pages):
1. `states.tsx`: `Empty` becomes a dashed, centred block with no nested glass, `ErrorState` and `Missing` take the `danger` roles (border and wash at 50% and 10%, `danger-fg` text), `Offline` reuses `Button` (solid, 44px below 768px) instead of a hand-made one.
2. Logs: the error row and message on the `danger` roles, row rules at 60%, a placeholder on the filter. Config: row rules at 60%. Doctor: removed diff lines on `danger-fg`, module cells on `bg`/60%, checkboxes on the `accent` colour.
3. Graph: view and fit/reset controls become `Chip` and `Button`, the tooltip and menu take `tooltipBox`, the 3D fallback notice the `warn` roles; project colours come from the `accent`, `delta`, `success` and `fg-muted` roles (hash of the root picks one) instead of an HSL hue, resolved from the tokens in the 3D stage.
4. Tests and stories for the new behaviour; stories in both themes for each state.
5. Verify with `just spa-test`, `just spa-stories`, `just spa-e2e`, `just check`; dark and light screenshots of Config, Doctor, Logs, Graph from `?sample` and of the states from Storybook.

Result (2026-10-08, Claude Code / claude-sonnet-5-5): `Empty` is a dashed, centred block with an icon and no second glass layer; `ErrorState` and the `Missing` alert of the text pages take the `danger` roles with the error icon; `Offline` sits on a glass card and reuses `Button` (solid, 44px below 768px) with its pending spinner instead of a hand-made button. Logs: error rows and messages on `danger`, row rules at 60%, a placeholder on the filter. Config rules at 60%. Doctor: removed diff lines on `danger-fg`, module cells on `bg`/60%, checkboxes on the `accent` colour. Graph: the 3D/2D/List switch is `Chip`, Fit all and Reset view are `Button`, the hover tooltip is the shared `tooltipBox`, the menu and the 3D-unavailable notice use the `surface`, `e3` and `warn` roles, and project colours come from four brand roles (`accent-fg`, `delta-fg`, `success-fg`, `fg-muted`) picked by the root hash instead of an HSL hue; the 3D stage resolves the `var()` with `resolveRole`, and its selection ring and in-scope edges take `accent-fg`. Shared files touched: `states.tsx` and `Missing` in `pages/parts.tsx`, because they are the shared states; no other shared component changed. New: `states.test.tsx`, a `resolveRole` test, and light, reconnecting and loading stories for the states. `just js`, `spa-typecheck`, `spa-test`, `spa-stories` (173) and `spa-e2e` (22) green.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
