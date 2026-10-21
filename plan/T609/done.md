The public [compress_prose](src/modes/compress.rs#L82) helper closes a fence at the next occurrence of exactly three backticks, without remembering the opening delimiter length or requiring a closing fence line. For a four-backtick Markdown block containing a triple-backtick example followed by `The really exact content`, Full compression removes `The really` inside the outer code block. The source promise to preserve fenced code verbatim is therefore violated. This is distinct from inline code-span protection and from stripping identifiers outside code.

Execution plan:

1. Add a golden regression for a four-backtick outer fence containing a triple-backtick example and filler-like code text. Assert exact preservation of the whole fenced region, including delimiters, spacing and newlines, at every intensity.
2. Recognize fence openings and closures by line position, delimiter kind and opening run length. A shorter internal run or an occurrence inside a code line must not close the block. Preserve unclosed blocks through the end as today; define and test supported Markdown fence forms.
3. Add controls for ordinary triple fences, longer matching closers, code lines containing backtick runs, and prose around the fence. Verify that inline-span handling remains independent and that prose outside the block still compresses.
4. Extend `tests/mode_bench.rs` with the reproduced fixture and an independent preservation oracle: its current `fence_bodies` helper also splits on raw triple-backtick substrings and cannot establish correctness for this case. Run focused mode tests, comparison tests and `just check`.

Check: the complete reproduced outer code block is byte-for-byte unchanged. An isolated executable imported the actual compression module by path, reproduced the four-backtick mutation, and passed ordinary triple-fence and double-backtick inline controls. This is a P3 library/benchmark defect, not observed production hook corruption; the native compression helper is not currently wired into those hooks.

Result: Fences in `src/modes/compress.rs` are found line by line with CommonMark rules (`find_fence`/`fence_run`): backtick and tilde, up to 3 spaces of indent, closer at least as long as the opener, unclosed fence to the end; a mid-line triple-backtick span stays inline.

Check: `a_longer_fence_survives_a_shorter_inner_run`, `fences_follow_markdown_opening_and_closing_rules`, `inline_triple_backtick_spans_stay_inline`, `tests/mode_bench.rs::compress_keeps_longer_fences_and_identifier_suffixes_exact`; snapshots unchanged.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
