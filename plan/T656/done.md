A merged unlocked worktree with no mtime, and a cache whose age could not be read, used to be eligible for removal. `gc` and `clean` now keep them with the reason `age unknown: a file or its mtime could not be read`. An established old age still removes; a fresh age still keeps.

Landed in `edc6c621` (#1055). This card only records that the check already holds on main. No real worktree or cache was deleted.

Check: missing age never authorizes removal; established fresh/old ages retain their policy. Do not validate by deleting real worktrees or caches.

Check result: `an_unknown_age_keeps_a_merged_worktree` keeps `None`, removes `Some(0)`, and keeps a fresh mtime inside the idle window. `only_an_idle_cache_outside_the_current_worktree_goes` keeps a cache with no mtime (`AGE_UNKNOWN`) and a cache modified inside the window, and lets an idle cache outside the current worktree go.

Status: done 2026-10-19
