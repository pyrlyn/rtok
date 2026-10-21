Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` with actual Store migrations and public APIs. [observations_for_files](crates/rtok-store/src/observations.rs#L122) joins `observation_files` to `observations`, then orders and limits joined rows without making observation IDs distinct. Insert an older observation linked to `a.rs`, then a newer one linked to both `a.rs` and `b.rs`; querying both paths with limit 2 returns the newer ID twice and omits the older matching observation. [obs_for_prompt](src/plugins/memory/mod.rs#L299) feeds these rows into [rrf_obs](src/plugins/memory/observe.rs#L180), which both gives duplicate IDs extra rank contributions and merges them after the store limit has already discarded other hits. The real ranking helper reduces this fixture to one hit despite two eligible observations.

Execution plan:

1. Add store regressions with multiple file links per observation and several distinct observations. Assert unique IDs, newest-first ordering and a limit counted in observations; include project isolation, overlapping paths and single-path controls.
2. Express an existence/subquery or distinct projection through Diesel so each observation participates once before ordering and LIMIT. Preserve global/project visibility and the existing empty-path behavior.
3. Add recall/ranking coverage proving extra matched files do not create duplicate contributions within one result list or starve older distinct hits. Preserve the intentional boost when an observation appears in separate FTS and file-recall lists.
4. Run focused store observation and memory recall tests, then `just check`.

Check: the two-path fixture returns both distinct observations, and fusion retains both. Executed evidence used the real in-memory Store and the verbatim production `rrf_obs`; single-path and other-project controls passed. Full hook dispatch and live host integration were not run.

Result: `observations_for_files` in `crates/rtok-store/src/observations.rs` filters by an id subquery on the links table instead of a join, so the limit counts distinct observations. Diesel only, no migration.

Check: `file_linked_observations_are_distinct_before_the_limit` failed before and passes after; `fusion_keeps_distinct_file_hits_and_boosts_cross_list_ones`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
