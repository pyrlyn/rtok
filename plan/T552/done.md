Confirmed by a function probe: [worth_parsing](src/plugins/checkpoint.rs#L261) requires the literal substring `"type":"user"` and rejects any otherwise ordinary user record containing `"tool_use_id"`. A valid one-line `{"type": "user", "message": {"content": "ship the patch"}}` loses its prompt, as does a compact user record containing both a tool_result and a typed text block. [user_text](src/plugins/checkpoint.rs#L386) correctly extracts text from both if the prefilter is bypassed. Completed T417 filters host-written prompts; these are uncovered false negatives in the prefilter's promised superset, not a request to undo that host filter.

Validation: an offline Rust probe compiled the verbatim extraction code and compared filter=true/false for both records. Only the filtered run lost the prompt; the equivalent compact text-only record survived.

Execution plan:

1. Extend the prefilter equivalence fixtures with optional JSON whitespace, mixed text/tool-result blocks, reordered keys and escaped field names.
2. Make the cheap filter conservative: ambiguous records must reach the structural parser; do not use tool_use_id presence as proof that no typed text exists.
3. Compare extracted prompts, counts, paths, skills and errors with filtering disabled; preserve T417's host-envelope exclusions and the existing large-transcript performance check.

Check: semantically equivalent valid JSONL yields identical checkpoint content regardless of formatting, mixed typed text survives, host-only content remains excluded, and `just check` passes.

Result: The checkpoint prefilter accepts spaced and mixed-content JSONL, so it agrees with the full parse.

Check: `prefilter_is_formatting_independent` failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
