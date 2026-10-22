T329 §6 (second half), on the scope T329.4 built. Depends on T329.4.

Check: `dead` over A's scope spares B's function only A calls, selecting B alone reports it; `affected` maps per project; an MCP reply stays under the cap with three linked projects; an edit in C updates its index under `watch`; `just check`.

Result: `graph::scope::dead` and `dead_json` run each project's dead rows, then drop a name that any other project in the scope references (new `Store::symbol_referenced_names`), so a symbol only a linked project calls stays live and is still reported per project; `graph dead` and `graph affected` resolve the scope like `impact` (`--project`, else path or cwd) and a scope of one prints the old output byte for byte. `scope::affected` reads `git diff` in every project of the scope (a non-git project adds nothing), starts the impact walk over the whole scope from each project's changed definitions, and lists tests per project with their commands; MCP `impact` without a name uses it for the project that holds `path`. Banners and skip notes now count against the one `max_tokens` cap of `symbol`, `callers`, `impact`, `explore`, `dead` and `affected`. `watch::run_scope` starts one watcher per project of the scope, each behind the T263 root guard, and re-reads the scope every 2 s, so a link made after start-up is watched; the pending-edits set is kept per root (`Ctx::graph_watch_pending(root)`). `docs/commands.md` (en, ru, uk) updated. #815.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
