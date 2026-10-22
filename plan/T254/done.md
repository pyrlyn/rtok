Creator request 2026-09-24, after T252. Tests still reach the developer's real home through `Config` paths nobody redirected:
- `tui::app::tests::hermetic` (called from `app.rs` and `view.rs`) redirects four paths in memory. It misses `stats.codex_dir` and every `setup.*` path, and the toggle tests reload `config.toml`, which drops the redirect. Every `App::new` snapshot scans the real `~/.codex/sessions`.
- Six `doctor.rs` tests start from `Config::default()`. Two read the real `~/.claude/projects`, and all read the real agent configs.
- `testutil::config_file_in` writes five paths into `config.toml`. The `setup.*` paths stay real for `tests/web.rs`, `graph_model.rs` and `stats_model.rs`.
- Two `web::model` tests hand-copy the doctor redirect on a `testutil::config` that is already rebased.

Plan:
- `Config::path_fields_mut` names each field with its dotted key, so one list serves both `~` expansion and the tests.
- `config_file_in` writes every absolute path of `config_in(dir)` into `dir/config.toml`.
- TUI: every `hermetic` caller uses `config_file_in(&dir)` plus its own overrides, and `hermetic` goes away.
- `doctor.rs` tests start from `testutil::config_in(&dir)` and drop the redirect lines that just repeat it.
- The copies in `web::model` go.
- A guard test fails when any path of `config_in` or `config_file_in` lies outside `dir` (relative paths aside), so a new `~` field cannot leak again.

Check: `cargo nextest run --lib` on `tui::`, `web::model`, `doctor::` and `testutil`, fast; `--test web`, `graph_model` and `stats_model` pass; `just check` green.

Result: `path_fields_mut` returns `(key, &mut PathBuf)` pairs built by a local macro from the field path itself, and `validate::set_all_with` batches `config set` into one write. `config_file_in` now writes every absolute path of `config_in`; the guard test `testutil::every_config_path_stays_in_dir` fails on the old helper with `setup.claude.settings_path = /Users/<you>/.claude/settings.json`. `hermetic` is gone, and the six `doctor.rs` tests and three `web::model` tests (including `session_detail_filters_snapshot_calls_by_id`, whose snapshot ran on a bare `Config::default()`) use the shared helpers. Correction to the finding: `Config::default()` leaves `~/x` literal, so those tests read nonexistent `./~/…` paths under the crate root, not the real home; the real leaks were the `load_from` configs (TUI) and the partial `config_file_in`. The 156 `tui::`, `web::model`, `doctor::`, `testutil` and `config::` unit tests run in 0.78 s; `just check` green (1729 tests).
