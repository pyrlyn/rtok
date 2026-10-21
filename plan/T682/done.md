Best competitor: — (rtok wins after #1079). Design: keep #1079; add `[N commits, +A/-D]`; drop Author when all equal.

Check: #1076 harness (`bench/c14`) shows keep lead on this category with full anchor retention.

Plan: add a `[N commits, +A/-D]` header and drop the Author column when every author is equal in `src/plugins/cmd` git-log formatter; fixture test for both, fail-first; measure with `bench/fixtures` (the #1076 harness is not on main).

Outcome: `git_log` in `src/plugins/cmd/formatters.rs` now leads with `[N commits, +A/-D]` (`[1 commit]` singular; the `+A/-D` part only when `--stat` printed insertion/deletion totals, summed by the new `stat_totals`) on the same line as the author/year header. #1079 already named a single author once in that header and never per commit line, so the "drop Author when all equal" half needed no code; `git_log_drops_the_author_when_all_are_equal` pins it (it passed before the change, so it is not fail-first). Fail-first: `git_log_header_counts_commits_and_stat_totals` and the updated `git_log_lists_every_commit_with_authors_when_mixed` failed on the old formatter (`left: "Ann · 2026"`, `right: "[2 commits, +5/-1] Ann · 2026"`) and pass now; the `git_log` and `git_log_long` goldens carry the header. Measured with `tests/bench_fixtures.rs` (o200k tokens, raw / before -> after, default / `--no-ref`): `git_log_20` 4027 / 529 -> 533 / 516 -> 520; `git_log_stat_5` 1275 / 756 -> 766 / 741 -> 751; `git_log_oneline_50` 1200 / 473 / 459 unchanged (the `--oneline` shape keeps the old cut). The header costs 4-10 tokens (about 1 point of saving), so the `git_log_stat_5` floor went 40 -> 39 and the `git_log_long` golden floor 70 -> 69; output stays below raw in every case and `expand <id>` still returns the raw bytes. The #1076 `bench/c14` harness is not on main, so no competitor comparison was run.

Status: done 2026-10-16
Model: Claude Code / claude-sonnet-5-5
