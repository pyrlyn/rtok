Confirmed by actual-source classification probes: [stem_read_only/writer_marker](src/plugins/guard/mod.rs#L324) accepts `sed 'w output' input`, `find . -fprint output`, and `git diff --output=output` as cacheable. It only checks selected writer markers such as sed -i, find -delete/-exec and redirection. These accepted variants write files, so their PostToolUse stores a dedup key instead of invalidating earlier reads; repeating them can be denied and previous file results can remain stale. T57.1/T322 cover other writer forms, not these options/scripts.

Validation: all three variants returned nonempty keys in an offline actual-source Rust probe. A separate system sed run in a temporary directory created a nonempty output file from the first example. The find/git side effects were not executed during the audit.

Execution plan:

1. Add classification regressions for sed's write/execute script commands, find output actions and git output-file options, plus a hook fixture proving a writer invalidates an earlier Read result.
2. Tighten eligibility at the shared classification layer: use a conservative supported read-only subset and reject write/execute forms or ambiguous script/option parsing. Reuse existing shell parsing helpers.
3. Retain common safe sed/grep/find/git listing cases; assert writer variants are allowed to execute again and clear stale read/Bash keys.

Check: all demonstrated writers return no dedup key, their post-tool events invalidate prior results, safe readers retain expected dedup, and `just check` passes.

Result: A print-only `sed` script parser (`src/plugins/guard/sed.rs`) treats anything unrecognised as a writer (`w`, `e`, `r`, `-f`, `-i`, `[` in a regex); `find` rejects `-fprint*`, `-fls`, `-ok*`; `git` rejects `--output` and its abbreviations. `sed_in_place` is gone.

Check: `side_effect_variants_of_read_only_stems_have_no_key`, `a_disguised_writer_invalidates_an_earlier_read`; `--test guard_check`, `--test hook_fail_open`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
