Confirmed in source and an isolated Rust probe: [Matcher::new](src/plugins/graph/walk.rs#L27) adds the positive override `**` before any configured includes. Override whitelists take precedence over gitignore, so this revives every ignored file, not only the requested subtree. With `.gitignore` containing `wanted/` and `unwanted/`, and include containing only `wanted/`, the full walk returns both `wanted/a.rs` and `unwanted/a.rs`. This can index large ignored trees and pollute graph results. T68.10 intended selective inclusion, not disabling ignore rules globally.

Validation: the offline probe compiled this revision's actual `walk.rs` with the real `ignore` crate, plus minimal configuration/extension-support stubs. It asserted both paths were returned. No repository index or live watcher was run.

Execution plan:

1. Add a real-repository fixture with two ignored subtrees, one explicit include and ordinary nonignored source.
2. Replace the blanket whitelist with eligibility handling that preserves normal gitignore behavior while admitting explicit includes; share the override interpretation with T545.
3. Cover no includes, nested patterns and include/exclude precedence without restricting ordinary supported source to the include list.

Check: only the explicitly included ignored subtree returns, ordinary source remains eligible, unrelated ignored source stays out, and `just check` passes.

Result: Graph `include` is additive: the ordinary walk takes only the exclude overrides, and each include pattern walks its literal-prefix subtree with ignore files off (a pattern with no directory part walks the whole root). `Matcher::walk()` replaces `walk_builder()`.

Check: `an_include_does_not_revive_unrelated_ignored_files` (4 cases). Committed with T545.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
