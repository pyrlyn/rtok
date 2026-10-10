# Agent notes — `graph`

**Owns** `src/plugins/graph/**` (`mod.rs`, `index.rs`), the `symbols` migrations, and the
`symbol_*` methods in `src/store/symbols.rs`.

**Contract**: the `Plugin` trait, `Ctx` and the host capabilities come from the published
`rtok-plugin-sdk` crate (`crates/rtok-plugin-sdk`), not from `crate::plugin` — import them as
`rtok_plugin_sdk::…`.

**Invariants**
- Native only (D6): never spawn, link or import an external graph tool. The index is built
  here from the tree-sitter-tags queries shared with `read`. The symbol store is SQLite only
  (P39: LadybugDB and Grafeo removed after measurement).
- Incremental: a file whose stat, then sha256, is unchanged is never re-parsed; removed files
  lose their rows; rows are scoped to the canonical root, so one store holds many repos.
- Every response is capped at `plugins.graph.max_tokens` and carries an archive id when truncated.
- Indexing never runs on the hook path; PostToolUse(Edit|Write) only marks a file stale.
- One writer per store: a watcher (P8d) is a thread inside `rtok mcp`, never a second process.
- Schema changes are a new `crates/rtok-store/migrations/NNNN_<slug>/up.sql`, never an edit to an applied one.
- T370 `rank.rs`: the file graph and its global PageRank are one `file_rank` document per root,
  rebuilt only when an index run changes the root. SessionStart reads that row and ranks in
  memory (no `symbols` scan, no process, D1/T428). A personalized rank is never stored.
- T454 `review.rs`: `rtok graph review` ranks a git diff (untested def 0.30, tested 0.05, security token +0.20, callers min(n/20, 0.10)). CLI only — do not add a sixth MCP tool. No `Measurement` on an unshortened answer. Not a dependency on any external graph tool (D6).
- T371 `cochange.rs`: git co-change pairs (last 300 commits, over 20 files skipped, count >= 2)
  are one `kv` document per root, recounted only when HEAD moves. `impact` prints the top 5
  partners of the defining file; `rank::build` adds them as edges at `COCHANGE_WEIGHT`. The map
  sees them from the next index run that changes the root. Not a repo: no pairs, no change.
- `impact` prints the flat `depth  path  scope` listing while it fits `impact_tokens`; past it `blast.rs`
  groups files (depth, stored file rank, refs), prints 3 lines each and ends in the cut line.
  `--all` / MCP `all` and `impact_tokens = 0` keep the flat listing. The LSP backend is not budgeted.
- T329.10 `text.rs`: the last-resort backend (`backend = "text"`, or `auto` with no server and no
  grammar file). It searches in process with `ignore` + `regex` through `read::search::text_files`
  and never starts a program (`tests/deps.rs` pins it). Answers are headed `(text)`; `dead` and the
  `to` chains of `impact` say "not available in text mode". Its `text.*` rows record time, not a saving.
- The plugin never writes SQL (D13). Storage is `src/store/symbols.rs` (`symbol_*` methods).
- `symbol` takes `name` or `names`. One name is the old text; several are headed `= name`
  and capped together. `symbol` / `callers` / `impact` / `explore` prepend
  `index <oldsha> head <newsha>` when the stored HEAD differs (full hex, no timestamp).
  `outline` does not. `worktree add` copies symbol rows from the main checkout.
- `drill.rs` (T329.14) answers the graph page's level 2 over `/ws` (`{"graph": ...}`); it reads the
  `symbol_graph.rs` scans only, so a name defined in more than 3 files draws no edge.
- `events.rs` (T329.15) writes the start, progress and end events of an MCP graph call to `graph_events`
  (fail open, never in a hook); `src/web/live.rs` is the one reader. The end event copies the call's
  `graph` `measurements` rows, so the page cannot show a number `rtok stats` does not. No source text.
- `tests/graph_contract.rs` pins the four tools through `rtok mcp`. Output changes are a
  task whose commit updates the expected strings; a backend must pass the file untouched.
- A tool listed by `mcp_tools()` is routed in `src/mcp.rs` `invoke` — `tools/list` and
  `tools/call` must agree (T8.9 found `impact` listed and unreachable).
- P35: T35.4 and T35.5 done (2026-09-11). **T35.3 batched store I/O is last** (user 2026-09-11).

**Checks**: `plan.md` T8.13–T8.17; earlier ones in `done.md`.
