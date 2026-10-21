T329.19 puts `ProjectRow.health` and `ProjectRow.scope_health` into `rtok graph projects`, `--json` and `/ws`; D27 (amended 2026-10-10, T346) requires the TUI to show them too, and the TUI `Model.projects` already carries both fields. Depends on T329.19. Done means: the TUI projects view shows each project's score (or `indexing` / `missing`) with the same colour bands as the page (green 80 and up, amber 50 to 79, red below 50), the selected project's breakdown (the three components, the reasons and their fixes) and the scope's lowest score.

Check: a TUI render test on fixture rows at 100, 60 with two reasons, `indexing` and `missing`; `tests/surface_parity.rs` lists the score on both surfaces; `just check`.

Done: `src/tui/health.rs` paints the registry row's `ProjectRow.health` and `scope_health` from the snapshot's rows (`Entry` in `src/tui/projects.rs` carries a copy, so there is no second store read). The Graph page's table gained `health` and `scope` columns: the number, or the word `indexing` or `missing` where there is none, coloured by the server's `Score.level` through the existing `theme` colours (good `OK`, warn `WARN`, bad and missing `ERR`, indexing `MUTED`); under the table the cursor project's line names its level and the three components, followed by up to three reasons as `component: text -> fix`. The scope's bare number has no level of its own, so it is banded by the new `score::level_of`, which `score::of` now also uses, so the 80 and 50 cut-offs live in one function for the server and the TUI. `ProjectRow.health` and `scope_health` became `pub`. With the `graph` feature off the page has no rows and the module paints nothing.

Deviations: The cursor row wears the table's highlight, which overrides the cell colours, so its level colour is shown by the breakdown line under the table instead; the other rows keep theirs. More than three reasons are cut off there (`rtok graph projects` has them all).

Check result: `tui::health::tests` (fixture rows at 100, 60 with two reasons, indexing and missing: cell colours are OK, WARN, MUTED and ERR, the cursor's breakdown shows the components, both reasons and their fixes, a 70 the server calls Bad is painted red and the scope 49 goes through `level_of`); `tests/surface_parity.rs::graph_health_score_exists_on_both_surfaces`; `just check` green.

Status: done 2026-10-10
Model: Claude Code / sonnet-5.5
