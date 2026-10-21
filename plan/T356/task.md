---
status: in progress
priority: P1
complexity: 2
readiness: 90%
agent: Claude Code / claude-opus-5-5
---

# T356. Never index `$HOME` or `/` as a graph root

Found 2026-10-02 (T352 research): `symbols` holds 617,319 rows (~120 MB plus indexes) under the root `/Users/listepo` — `.config/amp/plugins`, `.cursor/extensions`, `.grok/bundled`, `go/pkg/mod`, `.motive/node_modules`. The graph root is the `rtok mcp` process cwd (`std::env::current_dir()` in `src/plugins/graph/mod.rs`), so a server launched in the home directory (no `roots/list` answer yet, or a host without roots) walks the whole home on its first `symbol`/`callers`/`explore` call, and the rows never leave: `delete_symbols_missing` runs only when that same root is re-indexed.

Done when: the graph refuses a root that is the home directory or the filesystem root (an error that says to pass a path or open a project, no walk), and existing `symbols`/`symbol_stale` rows of such roots are dropped once (store retention or a migration). Indexing of real projects is unchanged.

Check: graph tests for both refused roots and an accepted project root; a store test that drops the home-root rows; `symbols` size on a copy of the real store before and after, recorded in this card.

Execution plan (after T352 lands — it adds `drop_vanished_symbol_roots` to retention): reuse `plugins::read::walk_root_ok` (T263: refuses `/` and the home directory for the watcher) in the graph's root choice (`src/plugins/graph/mod.rs`, the `current_dir()` call sites) and return `no project: pass path or open a project (roots)`; extend T352's root drop so a root that fails `walk_root_ok` is dropped like a vanished one. Tests: graph refuses home and `/`, accepts a temp project; retention drops a home-root row.

Progress (2026-10-21, audit): the code and both tests are in (#619). `index::run_with`, `run_changed_with` and `ensure` call `walk_root_ok` before any walk or write, so the CLI, the MCP tools and the watcher all get `no project root: rtok mcp runs in <dir>; pass a path or open a project` (`project::no_project_root`, T526). Retention calls `Store::drop_dead_symbol_roots` (`crates/rtok-store/src/retention.rs`) on every `rtok mcp`/`rtok proxy` start and on `rtok agents junk clear`; it drops `symbols`, `symbol_stale` and `extractor` rows of a root that is gone, `/` or `$HOME`. Tests: `index_refuses_home_and_filesystem_root_but_accepts_a_project` (`src/plugins/graph/index.rs`), `retention_drops_home_and_filesystem_roots` (`crates/rtok-store/src/tests_schema.rs`), `unwalkable_root_is_the_filesystem_root_or_home_only` (`src/fs.rs`). `just full-check` passes on this branch. Not measured: the `symbols` size on a copy of the real store. This agent session could not read `~/.rtok/rtok.db`, because the session's permissions only cover the worktree. The "before" is the 2026-10-02 figure above (617,319 home-root rows inside 282 MB of `symbols*`). Since #619, every `rtok mcp` start has run this retention on the live store, so a fresh copy probably already has 0 home-root rows. If so, the re-run records that as the "after", and a before/after pair on one copy is no longer possible. Left: the creator runs this protocol and records the numbers here, then the card moves to `done.md`.

Re-run protocol (macOS, the store is never touched in place):

1. `mkdir -p /tmp/t356 && sqlite3 ~/.rtok/rtok.db ".backup /tmp/t356/rtok.db"` (a consistent copy, WAL included).
2. Before: `ls -l /tmp/t356/rtok.db`; `sqlite3 -readonly /tmp/t356/rtok.db "SELECT root, COUNT(*) FROM symbols GROUP BY root ORDER BY 2 DESC LIMIT 5; SELECT COUNT(*) FROM symbols WHERE root IN ('/', '$HOME');"` (the shell expands `$HOME`).
3. Retention on the copy only: `RTOK_HOME=/tmp/t356 sh -c 'sleep 60 | rtok mcp'` (housekeeping runs on its own thread at start, and the open stdin keeps the server alive while it does). `HOME` stays real, so `$HOME` is the root that gets dropped.
4. After: repeat step 2. Record both rows in this card.
