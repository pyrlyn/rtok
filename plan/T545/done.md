Confirmed in source and an isolated Rust probe: [absorb_event](src/plugins/graph/watch.rs#L193) rejects gitignored paths before considering configured includes. Conversely, [Matcher::is_excluded](src/plugins/graph/walk.rs#L69) treats exclude patterns as literal prefixes, while the full walker interprets globs. Thus a file explicitly included from a gitignored subtree is indexed initially but its edits are ignored; a path matching `**/generated/**` is excluded from the full index but accepted as a watcher event and passed to incremental indexing. These are the event-path regressions of T68.10, distinct from T544's blanket full-walk whitelist.

Validation: the same offline probe compiled verbatim `gitignore`, `git_path`, `relevant` and `absorb_event` functions alongside actual `walk.rs`. It asserted rejection of the explicitly included file and acceptance of the glob-excluded file. This tests event filtering, not OS notification delivery or a database update.

Execution plan:

1. Add paired full-walk/event fixtures for an included ignored file and a glob-excluded supported file.
2. Reuse a consistent eligibility decision for walking, event intake and incremental indexing, with the same include/exclude precedence; preserve directory-rescan and .git exclusions.
3. Exercise edit and deletion events: included paths refresh/remove their symbols, excluded paths never enter the index, and irrelevant files do not reset the quiet window.

Check: full and incremental paths agree on eligibility for both fixtures, existing watcher debounce tests and `just check` pass.

Result: Watcher events follow the walk: exclude is gitignore-style with globs and parent directories and wins over include; `absorb_event` lets an included path through even when gitignored.

Check: `exclude_wins_over_include_and_understands_globs`, `events_follow_the_walks_include_and_exclude_rules`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
