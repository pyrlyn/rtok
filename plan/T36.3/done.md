**T36.3 `cmd` honours `plugins.cmd.rules` and `fail_tail_lines`** · — · `src/plugins/cmd/rules.rs`, `formatters.rs`, `run.rs`, `filter.rs`
Do: both keys are documented in `docs/config.md` and read nowhere: the user rule file is only tilde-expanded, and the non-zero-exit tail is the `FAIL_TAIL = 80` constant. Thread the `[plugins.cmd]` knobs into `pick()`/`apply()` through one settings value so both call paths agree.
Check: a rules file with a `match_cmd` rule changes the output for that command; `fail_tail_lines = 3` keeps three lines on a failing command. `cargo test --lib cmd`, 21/21 pass, including `user_rules_file_changes_output_for_match_cmd` and `exit_nonzero_fail_tail_lines` rstest cases.
Complexity: 3/5
Status: done 2026-09-11 · Model: Composer 2.5
