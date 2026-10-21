The 14 flat nav links become three labelled groups: Monitor (overview, stats, usage, calls, sessions, logs), Configure (plugins, hosts, skills, config, services, worktrees), Diagnose (doctor, graph). On `md` and up the sidebar collapses to icons; the choice is kept per browser like the theme, through one shared storage helper (`web/src/storage.ts`) that the theme now uses too. The bottom bar on phones stays one scrolling row.

Check: a story per state (expanded, collapsed, phone) passes axe; e2e still reaches every page from the nav.

Result: `web/src/Sidebar.tsx` holds the groups (`NAV_GROUPS`), a labelled `role="group"` per group, and a toggle with `aria-expanded` and a visible focus ring; collapsed links keep their name (screen-reader-only text plus `title`). A unit test pins that every page sits in exactly one group, and blocked storage still works. `Sidebar.stories.tsx` covers expanded, collapsed, phone and toggle. New rtok-only icon `brand/icons/ui/sidebar.svg`, since `@pyrlyn/brand` has no panel icon. `just check` green, `just spa-stories` 131 passed (axe), `just spa-e2e` 17 passed. #821.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
