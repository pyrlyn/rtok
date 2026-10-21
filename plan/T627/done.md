Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` using the verbatim production JSONC editor. [excise_member](src/agents/jsonc.rs#L241) skips whitespace but not comments when looking for the following comma. Removing `rtok` from `{"servers":{"rtok":1 /*keep*/,"other":2}}` returns `{"servers":{ /*keep*/,"other":2}}`, which no longer parses. The array equivalent, removing `"rtok worktree adopt" /*keep*/,` before another command, also leaves a leading comma. [Cursor post-create removal](src/agents/post_create.rs#L129) passes that result to `save` without validating it again; the ordinary MCP `remove_entry` wrapper instead rejects it before writing. T328 fixed replacement token boundaries, and T331.5 introduced shared removal coverage; neither records this comment-before-separator counterexample.

Execution plan:

1. Add object and array regressions with first/middle/last owned elements, block and line comments, trailing commas and multiple removals. Assert valid output, preserved foreign values and comments, and byte stability outside the removed span.
2. Make separator selection aware of JSONC trivia through the existing parser/scanner; share the correction between object and array removals.
3. Validate the final edited document before post-create settings can be written, preserving original bytes on errors. Exercise Cursor and the Devin/Windsurf caller with temporary files.
4. Run JSONC, Doctor removal and post-create preservation tests, then `just check`.

Check: removal leaves a parseable document containing the unrelated entry/command. Executed evidence compiled the actual editor and passed no-comment and last-element controls; the post-create filesystem write was source-traced, not executed against a real host.

Result: `excise_member` looks past comments for the following comma and keeps the comment; `pull_items` re-parses its result so post-create removal fails rather than writing a broken file.

Check: `removing_an_object_member_with_a_trailing_comment_keeps_the_text_valid`, `removing_array_elements_with_trailing_comments_keeps_the_text_valid`, cursor and devin removal tests.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
