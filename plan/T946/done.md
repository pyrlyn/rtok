`rtok stats --since 30d` takes 131 s on 2065 Claude JSONL files (3.7 GB), and `rtok web`'s
background Stats/Skills scan (T943) costs the same CPU every 5 minutes. `stats::collect` parsed
each file with `jsonl::parse_path`, which already records every `compact_boundary` in
`Parsed::compactions`, then `compact_events` read the file again and ran `serde_json` on every
line to count the same boundaries.

Decision (creator, 2026-10-20): reuse `parsed.compactions`; the T135 cache does not fit because
`fold_session` needs the whole `Parsed`, not per-file aggregates.

Do (Claude Code / claude-opus-5-5, 2026-10-20): `collect` adds `parsed.compactions.len()`;
`compact_events` and `is_compact_line` are gone. A file with one non-UTF-8 line now keeps its
other lines' compactions (`read_to_string` used to drop the whole file to 0).

Check result: `stats::tests::compactions_survive_a_non_utf8_line` (2 boundaries around a bad-UTF-8
line) fails on the old code with `compact 0` and passes with 2;
`compact_boundary_counts_once_per_event` unchanged and green; `just check` green. Release builds,
`rtok stats --since 30d` on an APFS snapshot of `~/.claude/projects` (559 sessions) and a
`.backup` of the store, load average 130-230 so wall time is noise (27-115 s for both): user CPU
15.6 / 14.5 / 14.6 / 15.1 / 15.3 s before, 11.9 / 11.6 / 11.8 / 12.5 / 11.8 s after (about -21%).
Output identical except the two dictionary `cache-safe saving` lines, which also differ between
two runs of the old binary: `dictionary::Tally::seen` ranks equal-weight tokens in `HashMap` order.

Status: done 2026-10-20
