**T34.8 small bugs behind duplicated helpers** · — · `src/plugins/cmd/formatters.rs`, `src/plugins/{archive,toon}/mod.rs`, `src/doctor.rs`, `src/web/model.rs`
Do: `rtok run` picked a filter rule when any argument word named a tool (`git commit -m "fix grep"` → the `grep` rule). `archive` and `toon` each re-read the live-zone boundary. `doctor` and the operator model each carried a raw HTTP GET, both cutting a body at its first blank line.
Check: `pick` keys on `argv[0]` (`rule_is_picked_by_the_command_not_an_argument`; goldens unchanged); `archive::outside_live_zone` shared (`live_zone_turns_are_untouched` green); one `doctor::http_get` with `split_once`.
Complexity: 2/5
Status: done 2026-09-10 · Model: Opus 5
