Source-confirmed: [clean](src/plugins/memory/files.rs#L56) strips periods from both ends of a path token. ".env" becomes "env" and is discarded by [mentioned](src/plugins/memory/files.rs#L95); "./src/lib.rs" becomes "/src/lib.rs" and is rejected as outside the fixture root. Worse, ".config/file" becomes "config/file" and links the wrong existing sibling if both exist. This affects note linking and prompt-driven recall. T374/T452 specify those features but do not track this normalization defect.

Execution plan:

1. Create existing .env, src/lib.rs, .config/file and config/file fixtures; assert hidden and dot-relative mentions resolve to the named file only.
2. Separate prose punctuation handling from path syntax so leading dots and dot-relative prefixes survive; preserve existing root confinement and position-suffix handling.
3. Cover Markdown/backtick mentions, trailing punctuation, line ranges, hidden-directory collisions and traversal rejection in both note linking and prompt recall.

Check: no-dot siblings are never substituted for explicitly named dot paths, valid mentions remain root-relative, outside paths stay excluded, and `just check` passes.

Result: `clean()` in `src/plugins/memory/files.rs` trims only `:,;!?` at the start of a mention (still `.:,;!?` at the end), so `.env`, `./src/lib.rs:12-30` and `.config/file` survive; note linking and prompt recall share `mentioned()`.

Check: `leading_dots_name_hidden_and_dot_relative_files` (hidden files, dot-relative, `#L3`, markdown/backtick, `../.env` and `.../x` rejected) failed before and passes after.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
