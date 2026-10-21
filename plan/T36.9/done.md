**T36.9 `~` expands for every path key** · — · `src/config/mod.rs`
Do: `report.out`, `bench.tasks`, `bench.configs.*` and `plugins.read.allow_paths` are missing from the expansion list, so `[report] out = "~/rtok-report.md"` fails with `No such file or directory` although `docs/config.md` says paths accept `~`.
Check: a test walks every `PathBuf` leaf of `Config::default()` and fails if one is not expanded.
Complexity: 2/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test -p rtok --lib default_expands_every_pathbuf tilde_expands` — 6 passed (`default_expands_every_pathbuf`, four `tilde_expands_for_every_path_key` cases, `expand_covers_bare_tilde_and_rtok_home_dir`).
