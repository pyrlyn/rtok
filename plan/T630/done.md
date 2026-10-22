Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` with the actual `rtok-config` crate. [page](src/model/tasks.rs#L156) reloads layers using `(&home, None, None)` and [configured](src/model/tasks.rs#L184) repeats that selection, dropping `cfg.loaded_from`. A custom file supplied through `--config` with task prefix `Z` loads correctly, but the verbatim `configured` helper returns false when the default file has no tasks section. If the default file instead specifies prefix `A`, the page's reload selects `A`; a control passing `loaded_from` retains `Z`. The board can consequently report an unconfigured project or use different task settings from the CLI launched with the same explicit config. The Config page's [config_page_text](src/model/mod.rs#L1019) calls the same `figment(&cfg.home, None, None)`, so it also displays values and provenance from the wrong file (source-traced). T441.13.7's refresh behavior does not cover this source-selection case.

Execution plan:

1. Add task-page refresh regressions for an explicit config file with no default file and with a conflicting default task configuration. Compare the board's settings and rows, and the Config page's values/provenance, with the CLI using that same config.
2. Carry the original selected config path through both refresh and provenance checks, reusing `Config::loaded_from`. Preserve reloading of project-level `.rtok.toml` changes.
3. Cover default-file, `RTOK_CONFIG`, explicit `--config`, missing/invalid files and fallback behavior without process-global environment mutation in tests.
4. Run config-layer/task-page tests and `just check`.

Check: task-page refresh and Config page rendering use the same selected user file as the process that created them. Executed evidence used real config extraction and the exact provenance predicate; background polling and browser rendering were source-traced, not run.

Result: `configured` and `page` in `src/model/tasks.rs` and `config_page_text` in `src/model/mod.rs` pass `cfg.loaded_from` to the layer loader instead of `None`.

Check: `configured_follows_the_selected_config_file`, `config_page_reads_the_selected_config_file`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
