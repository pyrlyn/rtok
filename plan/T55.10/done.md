**T55.10 One `cmd_stem`** · review 2026-09-17 · `src/agents/mod.rs`, `src/plugins/cmd/formatters.rs`, `src/measure/stats.rs`
Do: one `pub(crate) fn cmd_stem` in the feature-free `agents` module (`measure` builds without the `cmd` feature, so the `formatters` copy could not be shared); `formatters` re-exports it, `bash_family` and `is_rtok_bin` call it; the `cmd_stem_strips_windows_path_and_exe` test moved with the definition.
Check: behavior unchanged (stem rules byte-identical), `cmd_stem_strips_windows_path_and_exe` + `bash_family_*` + `is_rtok_bin` tests pass under `just check` and `just build-min`.
Complexity: 1/5 — three files, net −17 LOC.
Status: done 2026-09-17
Check result: targeted `nextest -E 'test(cmd_stem) or test(bash_family) or test(is_rtok_bin)'` 4 passed; full `just check` exit 0 in the isolation worktree (HEAD `11eef8b` + own three files; main checkout carries T55.8 WIP) — fmt, clippy `-D warnings`, nextest, build-min, jscpd 1.88 % (was 1.90 % before the dedup). 3 files, no new dependency.
Model: ZCode / GLM-5.3-Flash
