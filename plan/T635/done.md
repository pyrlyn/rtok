Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` in [field](plugins/claude/scripts/worktree.sh#L26). The sed expression copies the JSON string's encoded bytes and stops at an escaped quote. Given a JSON-encoded cwd `/tmp/quote"dir`, it returns `/tmp/quote\`; a cwd containing one backslash retains the JSON escape pair. [WorktreeCreate](plugins/claude/scripts/worktree.sh#L49) passes that wrong path to `git -C` in its fallback. Removal's directory check can silently skip the actual worktree for the same reason. D31/T159 require a functioning fallback, and absolute paths are not restricted to characters that avoid JSON escaping.

Execution plan:

1. Add script regressions with plain, spaced, quoted, backslash-containing and Unicode paths, using a missing/old rtok fallback and fake Git argv capture.
2. Decode the hook payload with a real JSON parser available under the supported fallback contract; do not replace one partial unescaper with another. Explicitly handle unavailable parsing and malformed fields.
3. Test create cwd/name and remove cwd/worktree_path independently, preserving slug sanitation, dirty-worktree refusal and stdout's single-path contract.
4. Run hook-script/fallback tests and `just check` on supported shell platforms.

Check: Git receives the decoded path exactly and removal does not silently skip an escaped path. Executed evidence ran the verbatim `field` function through POSIX sh with synthetic JSON; real Git worktree creation/removal and Windows were not run.

Result: `field()` in `plugins/claude/scripts/worktree.sh` decodes JSON with `jq`, else perl's core `JSON::PP`; without either, or for a non-object payload, the hook exits 1 naming `jq or perl`.

Check: `fallback_payload::*` (4) in `tests/plugin_scripts.rs`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
