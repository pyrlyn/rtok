Found by a bug-hunt pass over `src/plugins/guard/**`. The guard denies a repeat of a keyed (read-only) call as `duplicate; rtok expand <id>`. Four ways a mutating or different call was keyed, so its repeat was denied and never ran:

1. `read_only` split only on `|`: `ls && rm -rf build`, `cat a; rm a`, `ls || rm x`, `ls & rm x`, `ls` newline `rm x`, `cat $(rm x)` were keyed as read-only. Segments now split at every unquoted `|`, `;`, `&` (newlines become `;`), and a `$(` or backtick is never read-only.
2. `git branch` was read-only with any arguments, so `git branch -D x` was keyed. Only the listing forms are now (`-a`, `-r`, `-v`, `-vv`, `--all`, `--remotes`, `--verbose`, `--list`, `--show-current`).
3. `awk` programs that write (`system(`, `print >`, `| "cmd"`) were keyed; such a segment is no longer read-only.
4. The Read key ignored `offset`/`limit`/`pages`, so a second slice of a file was denied with the first slice's archive. A slice is now a key suffix after the path, so the `read\t{path}` prefix clear still reaches it.

Check: new `compound_commands_with_a_writer_are_not_keyed`, `git_branch_is_read_only_only_when_listing`, `awk_writers_are_not_read_only`, `read_slices_get_distinct_keys` (all failed before the fix); `just check` green.

Status: done 2026-10-01
Model: Claude Code / sonnet (reviewed by Claude Code / claude-opus-5-5)
