The public [compress_prose](src/modes/compress.rs#L82) helper changes `Edit foo_a now` to `Edit foo_ now`, `Open /tmp/a now` to `Open /tmp/ now`, and `Keep item1a here` to `Keep item1 here` at Full intensity. [strip_words](src/modes/compress.rs#L259) starts a removable word after an underscore, slash or digit but checks only the character following that word for identifier/path glue. Thus the final `a` is mistaken for an article and deleted. These are changes to names, not whitespace or filler compression. The existing `a.txt`/`a-b`/`a/b`/`a1` tests cover following glue, not preceding glue.

Execution plan:

1. Add Full and Ultra regressions for `foo_a`, `/tmp/a`, `item1a`, and analogous suffixes matching filler words. Assert preservation of the entire name, not merely the surrounding sentence. Retain standalone-article removal and existing right-boundary cases.
2. Decide whether a token is standalone from both original input boundaries. Treat preceding identifier/path punctuation and digits consistently with the current following-boundary protection, without making the decision from already edited output.
3. Cover combinations of left/right glue, Unicode neighbors, negation words and protected inline/fenced code; retain Lite behavior and deterministic output. Keep genuine prose article/filler removal working.
4. Add realistic identifier/path fixtures to the mode comparison tests, checking exact retained names before recording compression measurements. Run focused mode unit/benchmark tests and `just check`.

Check: the three reproduced names remain byte-identical while `Edit the file` still becomes `Edit file`. The isolated executable imported the actual compression module by path and reproduced all three changes; Lite and `a.txt` controls preserved their names. This is a P3 library/benchmark correctness defect: these native helpers are currently used by tests and comparisons, not wired into production hook compression.

Result: `strip_words` in `src/modes/compress.rs` checks the character before a word as well as after and treats non-ASCII letters as glue on both sides, so identifier and path suffixes are kept.

Check: `a_suffix_inside_a_name_is_kept` failed before and passes after; `a_suffix_name_next_to_code_is_kept`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
