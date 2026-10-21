Apply the approved shell and components; chart series colours come from the brand roles.

Check: `just spa-test`, `just spa-stories`, `just spa-e2e` green; dark and light screenshots of each page.

Plan (T414.2 shell and T414.3 components are in; the pages are audited against them in dark and light at 1440 and 390 px):
1. `ShareBar` in `web/src/ui/Marks.tsx`: one share bar with the chart tooltip, on the `accent` role for cost and tokens and the `delta` role for savings; it replaces the three hand-rolled bars in Overview (savings by plugin), Stats (cost per model) and Usage (periods).
2. Usage: the per-agent table keeps its agent column (it collapses at 1440 px when two half-width panels hold six columns), and the incomplete-cost panel takes the `warn` border the Overview alerts use.
3. Stats and Usage KPI grids take the Overview's `gap-3`.
4. Calls and Sessions: an idle detail column shows a hint instead of a hole (new `pages/DetailHint.tsx`; `Split` and `parts.tsx` stay untouched for T414.5 and T414.6).
5. Tests and stories for `ShareBar` and the hint; `just spa-test`, `spa-stories`, `spa-e2e`, `just check`; dark and light screenshots of the five pages.

Result (2026-10-08, Claude Code / claude-sonnet-5-5): the shell (T414.2) and components (T414.3) already carried the five pages, so the audit (dark and light, 1440 and 390 px) found these gaps and closed them. New `ShareBar` in `ui/Marks.tsx`: one share bar with the chart tooltip, `accent` for cost and tokens, `delta` for savings; it replaces the hand-rolled bars in Overview (savings by plugin), Stats (cost per model) and Usage (periods), which had no tooltip. Usage: the per-agent table with its six fixed-width columns lost its agent column inside a half-width panel at 1440 px, so it now takes the full width until 2xl; the incomplete-cost panel takes the `warn` border of the Overview alerts. Stats and Usage KPI grids use the Overview's `gap-3`; their saved cards use a new `saved` tone on `Kpi` (the `delta` coral, as the hero, the trend chart and the bars) instead of the success green. Calls and Sessions show a dashed hint in the idle detail column (`pages/DetailHint.tsx`) instead of a hole; `Split` and `parts.tsx` are untouched. The only shared-component change is the one-line `saved` tone in `Kpi`. Chart series already drew on the brand roles (T414.15). New tests: `ShareBar` (width, clamp, hidden from assistive tech) and the Calls hint; new stories for `ShareBar` and the `saved` tone, dark and light. `just spa-typecheck`, `spa-test` (259), `spa-stories` (172) and `spa-e2e` (22) green. Screenshots stay out of `web/screenshots/` (T414.7 re-shoots them).

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
