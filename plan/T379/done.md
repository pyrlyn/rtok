Found 2026-10-03 while reviewing T364. `report.since = "7x"` passes `config set` and `config validate` (`ok`), and `rtok report` then fails with `Error: bad --since unit in 7x` although no flag was passed. T364 gave `stats.since` a rule and made the parser name its source; `report.since` got neither the rule nor the early flag parse.

Done when: `validate.rs` runs `measure::stats::parse_since_from` on `report.since`, so `set` and `validate` reject it naming the key; `rtok report --since` is parsed before it merges into `report.since`, so a flag error says `--since` and a config error says `report.since`.

Check: validate tests reject `7x` / `d` / `-1d` / `""` for `report.since` and accept `30d`, `12h`, `7`; a refused `set` leaves the file unchanged; an e2e run of `rtok report` with `report.since = "7x"` names `report.since`, and with `--since 7x` names `--since`; `just check`.

Execution (Claude Code / claude-opus-5-5): stacked on T364 (#650). Add `"stats.since" | "report.since"` to the one rule in `validate::check_leaf`; in `Cmd::Report` parse `since` with `parse_since` before `report_flags`, as `Cmd::Stats` does; tests in `validate.rs` and `tests/commands_e2e.rs`.

Result (2026-10-03, Claude Code / claude-opus-5-5): `report.since` joins `stats.since` in the one `validate::check_leaf` rule, so `config validate` and `config set` reject `7x`, `d`, `-1d` and `""` naming the key and the line, and a refused `set` leaves the file unchanged (`a_malformed_since_window_is_rejected` now covers both keys). `rtok report --since` is parsed before it merges into `report.since`, as `rtok stats` does since T364, so a bad flag says `--since` and a bad config value says `report.since` (`commands_e2e::report_since_errors_name_their_source`).
