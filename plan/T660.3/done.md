Needs T660.1; lands before T660.4. With worktrees under `<main>/.rtok/worktrees`, several places would see them as part of main:

- graph walk (`plugins/graph/walk.rs`), `search`/`tree` (`read/search.rs`) and the watcher (`graph/watch.rs`, root `.gitignore` only) descend into them when `.rtok/` is not ignored: skip any directory holding a `.git` file (a nested checkout), like `worktree/list.rs::walk` already does.
- `worktree/gc.rs` and `worktree/clean.rs` call a worktree current by `here.starts_with(dir)`, so from a nested worktree main counts as current too: pick the deepest match.
- `worktree/origin.rs` names `<main>/.rtok/worktrees` its own `rtok` pool.
- `worktree/claim.rs::copy_symbol_index` copies no rows under `.rtok/`.

Check: a test per bullet with a worktree nested under `.rtok/worktrees` and no ignore rule; `just check`.

Result: `fs::is_linked_worktree` (a `.git` file whose admin dir has a `commondir`) is the one test for "another checkout": `search`/`tree` and the graph walk skip such a directory below the walk root through `skip_git`, and the watcher drops its events (`Matcher::in_linked_worktree`). Submodules and nested clones stay walked: they are this project's files, so the card's "any `.git` file" was narrowed. `worktree::deepest` picks the current worktree for `gc`, `clean`, `claim::linked` and `complete_pending`; `origin.rs` names `.rtok/worktrees` the `rtok` pool; `copy_symbol_rows` takes directories to skip and `copy_symbol_index` passes `.rtok`. Tests: `a_nested_linked_worktree_is_not_walked_into`, `search_and_tree_skip_a_nested_linked_worktree`, `events_inside_a_nested_linked_worktree_are_ignored`, `clean_and_gc_take_the_deepest_worktree_as_current`, `a_new_worktree_inherits_no_symbol_rows_under_rtok`, the `origin` pool row.
Model: Claude Code / claude-opus-5-5 (adopted from a stalled claude-opus-5-5 session's uncommitted work, reviewed)
