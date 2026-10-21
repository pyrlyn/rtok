Best competitor: lean-ctx 66.6 %. Design: ls -l → `name size` with dirs first, drop perms unless non-default (`x` bit, symlink target kept as `a -> b`); group by extension when > 30 entries.

Check: #1076 harness (`bench/c14`) shows ≥ 72 % (= rtk) on this category with full anchor retention.

Plan: #1077 already ships `ls -l` (dirs on one line, human sizes, exec marker, symlink target). Remaining: dirs first, group by extension above 30 entries, `ls -la` dotfiles. Extend `ls_l` in `compact.rs`; measure against the current golden `ls*` fixtures and raise their `min_saving:` floors to the new numbers.

Result: `ls_l` in `compact.rs` now renders through `render_files`: above 30 regular files in one listing, files are grouped by extension (`*.rs (28): plugin_01 1.1K, …`; name = stem + the header's extension, a lone file of its extension stays a plain `name size` line, `*` exec marker kept per member); three or more dotfiles (`ls -la`) share one `dotfiles: .gitignore 412B, …` line; symlinks keep `a -> b`. Dirs-first, human sizes, exec marker and symlink targets were already shipped by #1077. Every name still appears once, so path retention is 100 %. Measured by golden bytes (the #1076 bench/c14 harness is not on main, so no lean-ctx / rtk comparison was run): new golden `ls_many` (`ls -la`, 49 entries) 2762 -> 712 B (74 %, floor 73); the extension grouping alone is worth about 8 % against the previous per-file rendering (about 770 B), because every name and size has to stay. The existing `ls` / `ls_la` goldens are unchanged (they are below the grouping threshold) so their floors stay.

Status: done 2026-10-16
Model: Claude Code / claude-sonnet-5-5
