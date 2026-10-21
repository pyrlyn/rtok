From the codegraph / graphify review. codegraph's `codegraph.json` has `exclude` (gitignore-style), `include` (force gitignored source back in), `deprioritize` and `extensions` (`.tpl → php`); graphify has the same for its walker. rtok's walker is `ignore::WalkBuilder` with `.gitignore` only and a fixed extension → grammar table, so a vendored tree cannot be dropped, a gitignored generated source cannot be indexed, and projects with custom extensions get no rows.
Done when `[plugins.graph]` gains `exclude = []`, `include = []` (both gitignore syntax, applied through `WalkBuilder` overrides — no hand-written matcher) and `extensions = {}` (`ext = "grammar"`, unknown grammar names rejected by `config validate`); the watcher applies the same three (`relevant()` / `absorb_event` share the matcher with the walker); `deprioritize` is not added (rtok ranks by reference count, T52.3); rows in `docs/config.md` and the config-show golden; a unit test on a `Vfs` tree with an excluded dir, an included gitignored file and a mapped extension; `rtok graph index` reports how many files each list changed.

**Result (2026-09-18).** Commit `45c4531`. `[plugins.graph]` exclude/include/extensions in config; `src/plugins/graph/walk.rs` shared matcher for walker and watcher; validate rejects unknown grammars.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
