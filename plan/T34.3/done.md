**T34.3 one test fixture instead of eleven** · — · `src/testutil.rs`, `src/plugin.rs`, unit tests in `expand`, `mcp`, `cmd`, `memory`, `read`, `graph`, `guard`, `tui`
Do: eleven test modules each built a temp dir and a `Config` pointing into it; `guard` reused one fixed dir across runs, and every `tui` test shared (and deleted) one `rtok-tui-<pid>` dir, so parallel tests failed with os error 22. `Runtime::open` and `in_memory` duplicated their construction.
Check: `testutil::{tmp_dir, config, runtime}` (a fresh dir per call, `same_tag_gives_distinct_dirs`); `Runtime::with_store`. New tests: `bash_repeat_behind_cd_prefix_denies` (guard's Bash key was untested), `dot_dot_escape_is_err` (read's lexical escape).
Complexity: 2/5
Status: done 2026-09-10 · Model: Opus 5
