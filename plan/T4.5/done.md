**T4.5 `search` + `tree`** · T4.1 · `src/plugins/read/search.rs`
Do: `search(pattern, path, max=50)` regex over files respecting `.gitignore` (use `ignore` crate), output `path:line: snippet` (≤ 120 chars); `tree(path, depth=2)` compact listing with sizes.
Check: `search("fn main", ".")` finds `src/main.rs`; results never exceed `max`.
Status: done 2026-09-02 · Check: `search_fn_main_finds_src_main`; `search_respects_max`. `make check` green. Deviation: also `read/mod.rs`, `mcp.rs`. New deps: `ignore` (gitignore walk), `regex` (pattern).
