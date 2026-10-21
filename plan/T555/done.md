Confirmed by an actual-source function probe: [cache_key](src/plugins/guard/mod.rs#L255) passes command text through [norm_cmd/collapse](src/plugins/guard/mod.rs#L409), whose split_whitespace/join normalizes inside quotes too. `cat 'a  b'` and `cat 'a b'` therefore produce the same nonempty Bash key in one cwd, although they read distinct files. After the first result is archived, [pre_tool](src/plugins/guard/mod.rs#L57) can deny the first execution of the second command and offer the wrong file's result. T55.9/T445 preserved cd/cwd distinctions but not quoted argument bytes.

Execution plan:

1. Add two files with different contents and one versus two spaces in their names; cache the first command's result and assert the second command is allowed.
2. Preserve quoted and escaped argument bytes when normalizing keys; only collapse syntactically insignificant whitespace. Fail open when the existing shell helpers cannot establish a safe identity.
3. Cover single/double quotes, literal tabs/newlines inside arguments, escaped spaces and supported rtok run wrappers; retain dedup for equivalent outside-quote whitespace.

Check: different paths never collide, ordinary equivalent repeats still deny with the correct archive, and `just check` passes. The probe confirmed key equality; full hook regression is part of the fix.

Result: `flatten()` in `src/plugins/guard/mod.rs` replaces `collapse`: whitespace collapses and newlines become `;` only outside quotes and escapes; the `rtok run` wrapper is stripped first so Pre and Post keys match. An unterminated quote gives no key (fail open).

Check: `bash_key_keeps_whitespace_inside_arguments`, `quoted_whitespace_variant_is_not_denied_as_a_duplicate`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
