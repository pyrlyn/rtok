**T21.2 `graph index` shows progress** · T8.1 · `src/plugins/graph/index.rs`, `src/render.rs`, `src/cli.rs`
Do: a cold index walks thousands of files in silence. Split `index::run` into `run_with(cx, root, dry_run, pb)` and keep `run` as the same call with `ProgressBar::hidden()`, so the MCP tool and the background watcher — neither of which owns a terminal — are unchanged and un-branched. `rtok graph index` passes a real spinner from `render::spinner`.
Check: the bar's position equals the report's file count, so it counts the walk rather than decorating it; indicatif draws nothing when stderr is not a terminal, so piped output stays byte-clean; `just check` green.
Status: done 2026-09-09 · Model: Opus 5
Check result: green — `the_progress_bar_counts_the_files_the_walk_reached` (7 `.rs` files and one `.md`: `indexed` is 7 and `pb.position()` is 7), the six existing `graph::index` tests unchanged, `just check` clean.
Deviation: none. `ProgressBar::hidden()` is the reason there is no `Option<&ProgressBar>` and no `cfg` in the walk — the no-op bar is indicatif's own answer to a caller with nothing to draw on.
